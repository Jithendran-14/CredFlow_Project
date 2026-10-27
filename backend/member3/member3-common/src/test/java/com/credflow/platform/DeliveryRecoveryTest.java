package com.credflow.platform;

import com.credflow.platform.entity.DeliveryRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRecoveryTest {

    private Platform p;

    private EntityManager em;

    private Platform.JobObserver observer;

    private DeliveryEngine engine;

    private DeliveryRow row;

    private Platform.Job job;

    private UUID jobId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        p = spy(new Platform(new MockEnvironment().withProperty("credflow.service-name", "orchestration-service").withProperty("credflow.urls.consent", "http://unused.local").withProperty("credflow.delivery.max-attempts", "2"), new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get()).when(p).tx(any());
        em = mock(EntityManager.class);
        observer = mock(Platform.JobObserver.class);
        when(observer.canRunJob(any(), any(), any())).thenReturn(true);
        engine = new DeliveryEngine(p, mock(org.springframework.kafka.core.KafkaTemplate.class), List.of(), List.of(observer));
        engine.em = em;
        jobId = UUID.randomUUID();
        row = new DeliveryRow();
        row.setId("JOB:" + jobId);
        row.setApplicationId(UUID.randomUUID());
        row.setKind("JOB");
        row.setStatus("PENDING");
        row.setNextAttempt(Instant.EPOCH);
        row.setTarget("consent");
        job = new Platform.Job("consent", "/api/v1/internal/verifications", p.json(Map.of("verificationId", jobId, "applicationRevision", 1)), jobId.toString(), new Platform.Actor("HUMAN", "seller", null), "original-trace");
        row.setPayload(p.write(job));
        TypedQuery<String> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(String.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(row.getId()));
        when(em.find(DeliveryRow.class, row.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(row);
    }

    private void failHttp() {
        doThrow(new ResourceAccessException("timeout")).when(p).call(eq("system"), eq(HttpMethod.POST), anyString(), any(), anyString(), eq("original-trace"));
    }

    @Test
    void pendingFailureNotifiesWorkflowAndRetainsTheSameJob() {
        failHttp();
        engine.run();
        assertEquals("PENDING", row.getStatus());
        assertEquals(1, row.getAttempts());
        verify(observer).onJobState(row.getApplicationId(), jobId, job, "PENDING", "ResourceAccessException");
    }

    @Test
    void exhaustedFailureNotifiesWorkflowAsDead() {
        row.setAttempts(1);
        failHttp();
        engine.run();
        assertEquals("DEAD", row.getStatus());
        verify(observer).onJobState(row.getApplicationId(), jobId, job, "DEAD", "ResourceAccessException");
    }

    @Test
    void successfulRetryNotifiesOnlyTheCompletedJob() {
        row.setAttempts(1);
        doReturn(p.json(Map.of("status", "QUEUED"))).when(p).call(anyString(), any(), anyString(), any(), anyString(), anyString());
        engine.run();
        assertEquals("DONE", row.getStatus());
        verify(observer).onJobState(row.getApplicationId(), jobId, job, "DONE", null);
    }

    @Test
    void obsoleteJobIsCompletedWithoutSharingData() {
        when(observer.canRunJob(any(), any(), any())).thenReturn(false);
        engine.run();
        assertEquals("DONE", row.getStatus());
        assertEquals("OBSOLETE_JOB", row.getError());
        verify(p, never()).call(anyString(), any(), anyString(), any(), anyString(), anyString());
        verify(observer).onJobState(row.getApplicationId(), jobId, job, "SKIPPED", "OBSOLETE_JOB");
    }

    @Test
    void administratorReplayUpdatesHealthAndReturnsTheStableOperation() {
        row.setStatus("DEAD");
        var controller = new DeliveryController(p, List.of(observer));
        controller.em = em;
        doNothing().when(p).requireScope(any(), eq("ops.replay"));
        doReturn(true).when(p).hasRole(any(), eq("ADMIN"));
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "RS256").subject("admin").build();
        Object result = controller.replay(jwt, "JOB", jobId);
        assertEquals("PENDING", row.getStatus());
        assertEquals(0, row.getAttempts());
        assertEquals(Map.of("operationId", jobId, "status", "PENDING"), result);
        verify(observer).onJobState(row.getApplicationId(), jobId, job, "REPLAYED", null);
    }

    @Test
    void scheduledHealthUpdatesKeepTheOriginalTraceAndRestoreCallerContext() {
        org.slf4j.MDC.put("correlationId", "scheduler-parent");
        doAnswer(call -> {
            assertEquals("original-trace", org.slf4j.MDC.get("correlationId"));
            return null;
        }).when(observer).onJobState(any(), any(), any(), eq("PENDING"), anyString());
        failHttp();
        try {
            engine.run();
            assertEquals("scheduler-parent", org.slf4j.MDC.get("correlationId"));
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
    }

    @Test
    void incomingEventsMustUseTheApplicationIdAsTheirKafkaKey() {
        var event = new Platform.Event(UUID.randomUUID(), "application.submitted", "1.0", Instant.now(), "application-service", row.getApplicationId(), 1, "original-trace", null, p.json(Map.of()), new Platform.Actor("SYSTEM_CLIENT", "application-service", null), null);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>("application.submitted", 0, 0L, UUID.randomUUID().toString(), p.write(event));
        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> engine.receive(record));
        assertEquals(400, failure.getStatusCode().value());
        verify(em, never()).persist(any());
    }

    @Test
    void eligibilityWireUsesExactContractAndKeepsTheOperationResponse() {
        ((MockEnvironment) p.env).withProperty("credflow.urls.eligibility", "http://eligibility.test");
        var payload = p.json(Map.of("evaluationRequestId", jobId, "applicationId", row.getApplicationId(), "applicationRevision", 1, "verificationId", UUID.randomUUID(), "asOfDate", "2026-10-16", "authorisedRetrievalReference", "trusted-retrieval-104", "correlationId", "original-trace", "causationId", UUID.randomUUID()));
        job = new Platform.Job("eligibility", "/internal/v1/eligibility/evaluations", payload, jobId.toString(), new Platform.Actor("HUMAN", "seller", null), "original-trace");
        row.setPayload(p.write(job));
        UUID operation = UUID.randomUUID();
        var response = p.json(Map.of("operationId", operation, "status", "PENDING", "statusUrl", "/api/v1/operations/" + operation));
        doAnswer(call -> {
            com.fasterxml.jackson.databind.JsonNode wire = call.getArgument(3);
            assertEquals(6, wire.size());
            assertFalse(wire.has("correlationId"));
            assertFalse(wire.has("causationId"));
            assertEquals("trusted-retrieval-104", wire.path("authorisedRetrievalReference").asText());
            return response;
        }).when(p).call(eq("system"), eq(HttpMethod.POST), eq("http://eligibility.test/internal/v1/eligibility/evaluations"), any(), eq(jobId.toString()), eq("original-trace"));
        doAnswer(call -> {
            assertEquals("original-trace", org.slf4j.MDC.get("correlationId"));
            assertEquals("RUNNING", row.getStatus());
            return null;
        }).when(observer).onJobResponse(row.getApplicationId(), jobId, job, response);
        engine.run();
        assertEquals("DONE", row.getStatus());
        verify(observer).onJobResponse(row.getApplicationId(), jobId, job, response);
        assertTrue(p.read(row.getPayload(), Platform.Job.class).payload().has("causationId"));
    }

    @Test
    void invalidUpstreamOperationResponseKeepsTheJobRetryable() {
        var response = p.json(Map.of("status", "PENDING"));
        doReturn(response).when(p).call(anyString(), any(), anyString(), any(), anyString(), anyString());
        doThrow(new IllegalArgumentException("ELIGIBILITY_OPERATION_RESPONSE_INVALID")).when(observer).onJobResponse(row.getApplicationId(), jobId, job, response);
        engine.run();
        assertEquals("PENDING", row.getStatus());
        assertEquals(1, row.getAttempts());
        verify(observer, never()).onJobState(any(), any(), any(), eq("DONE"), any());
    }
}
