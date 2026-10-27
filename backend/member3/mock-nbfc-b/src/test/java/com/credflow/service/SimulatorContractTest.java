package com.credflow.service;

import com.credflow.dto.NbfcBReceipt;
import com.credflow.entity.SimCallback;
import com.credflow.entity.SimCase;
import com.credflow.platform.Platform;
import com.credflow.repository.NbfcBSimulatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimulatorContractTest {

    private Platform platform;

    private EntityManager em;

    private NbfcBSimulatorService simulator;

    private Jwt jwt;

    private final List<SimCallback> callbacks = new ArrayList<>();

    private static final String LENDER_ID = "22222222-2222-4222-8222-222222222222";

    @BeforeEach
    void setup() {
        platform = spy(new Platform(new MockEnvironment(), new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class)));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get()).when(platform).tx(any());
        doNothing().when(platform).machine(any(), anyString(), anyString());
        em = mock(EntityManager.class);
        doAnswer(invocation -> {
            Object persisted = invocation.getArgument(0);
            if (persisted instanceof SimCallback callback) {
                callbacks.add(callback);
            }
            return null;
        }).when(em).persist(any());
        simulator = new NbfcBSimulatorService(new NbfcBSimulatorRepository(em), platform, "integration-service", "nbfc-b", LENDER_ID, "http://localhost:8085");
        jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").claim("sub", "integration-service").build();
    }

    private ObjectNode request() {
        ObjectNode request = platform.json(Map.of()).deepCopy();
        for (String key : List.of("submissionId", "applicationId", "productId", "financialSnapshotId", "consentId", "eligibilityResultId", "verificationId")) {
            request.put(key, UUID.randomUUID().toString());
        }
        request.put("lenderId", LENDER_ID);
        request.put("applicationRevision", 1);
        request.put("consentVersion", 1);
        request.put("sellerSubject", "seller-123");
        request.put("marketplaceSellerId", "MKT-123");
        request.put("registeredBusinessName", "Synthetic Shop");
        request.put("loanPurpose", "WORKING_CAPITAL");
        request.put("requestedAmount", "1000.00");
        request.put("currency", "INR");
        request.set("originActor", platform.json(new Platform.Actor("HUMAN", "seller-123", null)));
        return request;
    }

    private SimCase application() {
        SimCase application = new SimCase();
        application.setSubmissionId(UUID.randomUUID());
        application.setId("nbfc-b:" + application.getSubmissionId());
        application.setApplicationId(UUID.randomUUID());
        application.setExternalId("BA-" + application.getSubmissionId());
        application.setLenderCode("nbfc-b");
        application.setLenderId(LENDER_ID);
        application.setRevision(1);
        application.setStatus("UNDER_REVIEW");
        application.setRequestedAmount(new BigDecimal("1000.00"));
        application.setStateJson(platform.write(Map.of("request", Map.of("correlationId", "original-journey"))));
        return application;
    }

    @SuppressWarnings("unchecked")
    private void externalLookup(SimCase application) {
        TypedQuery<SimCase> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(SimCase.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setLockMode(any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(application));
    }

    private ObjectNode decision(String type) {
        return platform.json(Map.of("decisionId", UUID.randomUUID(), "decision", type)).deepCopy();
    }

    private ObjectNode envelope(String type, JsonNode data) {
        return platform.json(Map.of("type", type, "data", data, "originActor", new Platform.Actor("HUMAN", "officer-1", LENDER_ID))).deepCopy();
    }

    private ObjectNode offer() {
        return platform.json(Map.ofEntries(Map.entry("offerId", UUID.randomUUID().toString()), Map.entry("offeredAmount", "900.00"), Map.entry("feesAmount", "0.00"), Map.entry("currency", "INR"), Map.entry("annualInterestRatePercent", "12.5"), Map.entry("interestRateType", "FIXED"), Map.entry("repaymentFrequency", "MONTHLY"), Map.entry("tenureMonths", 12), Map.entry("version", 0), Map.entry("termsReference", "synthetic/terms/v1"), Map.entry("expiresAt", Instant.now().plusSeconds(3600).toString()))).deepCopy();
    }

    @Test
    void submitDuplicateReturnsOriginalReceiptAndPayloadConflictIsRejected() {
        ObjectNode request = request();
        var saved = org.mockito.ArgumentCaptor.forClass(SimCase.class);
        Object first = simulator.submit("nbfc-b", request, jwt, "journey-one");
        verify(em).persist(saved.capture());
        SimCase application = saved.getValue();
        when(em.find(SimCase.class, application.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(application);
        request.put("correlationId", "untrusted-body-trace");
        Object duplicate = simulator.submit("nbfc-b", request, jwt, "different-retry-header");
        assertEquals(first, duplicate);
        assertEquals("journey-one", platform.read(application.getStateJson(), JsonNode.class).path("request").path("correlationId").asText());
        request.put("requestedAmount", "999.00");
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () -> simulator.submit("nbfc-b", request, jwt, "journey-one"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(0, callbacks.size());
    }

    @Test
    void sellerReferenceDoesNotRequireAnUnrelatedJwtSubject() {
        ObjectNode input = request();
        input.remove("sellerSubject");
        input.put("marketplaceSellerId", "SELLER-1001");
        Object receipt = simulator.submit("nbfc-b", input, jwt, "opaque-seller-id");
        assertInstanceOf(NbfcBReceipt.class, receipt);
        var saved = org.mockito.ArgumentCaptor.forClass(SimCase.class);
        verify(em).persist(saved.capture());
        JsonNode stored = platform.read(saved.getValue().getStateJson(), JsonNode.class).path("request");
        assertFalse(stored.has("sellerSubject"));
        assertEquals("SELLER-1001", stored.path("marketplaceSellerId").asText());
    }

    @Test
    void manualReviewRequiresQuestionsAndSendsSameTaskToTheWorkflow() {
        SimCase application=application();externalLookup(application);
        ObjectNode data=decision("MANUAL_REVIEW");
        assertThrows(RuntimeException.class,()->simulator.command("nbfc-b",application.getExternalId(),UUID.randomUUID(),envelope("DECISION",data),jwt,"manual-trace"));
        assertTrue(callbacks.isEmpty());assertEquals("UNDER_REVIEW",application.getStatus());
        data.put("informationRequestId",UUID.randomUUID().toString());
        data.putArray("questions").addObject().put("questionId","sales").put("prompt","Explain the sales decline in June.");
        simulator.command("nbfc-b",application.getExternalId(),UUID.randomUUID(),envelope("DECISION",data),jwt,"manual-trace");
        assertEquals("ACTION_REQUIRED",application.getStatus());assertEquals(2,callbacks.size());
        JsonNode question=platform.read(callbacks.get(1).getPayload(),JsonNode.class).path("data");
        assertEquals(data.path("informationRequestId"),question.path("informationRequestId"));
        assertEquals(data.path("questions"),question.path("questions"));
    }

    @Test
    void decisionTimeAndCallbackIdentityStayStableAfterLostCommandResponse() {
        SimCase application = application();
        externalLookup(application);
        UUID commandId = UUID.randomUUID();
        ObjectNode decision = decision("APPROVED");
        String originalTime = Instant.now().minusSeconds(1).toString();
        decision.put("decidedAt", originalTime);
        ObjectNode command = envelope("DECISION", decision);
        simulator.command("nbfc-b", application.getExternalId(), commandId, command, jwt, "original-journey");
        String callbackPayload = callbacks.getFirst().getPayload();
        UUID callbackId = callbacks.getFirst().getId();
        simulator.command("nbfc-b", application.getExternalId(), commandId, command, jwt, "new-retry-header");
        assertEquals(1, callbacks.size());
        assertEquals(callbackId, callbacks.getFirst().getId());
        assertEquals(callbackPayload, callbacks.getFirst().getPayload());
        JsonNode callback = platform.read(callbackPayload, JsonNode.class);
        assertEquals(originalTime, callback.path("data").path("decidedAt").asText());
        assertEquals("original-journey", callback.path("correlationId").asText());
        assertEquals(commandId.toString(), callback.path("causationId").asText());
        assertEquals(originalTime, platform.read(application.getStateJson(), JsonNode.class).path("decisions").get(0).path("decidedAt").asText());
    }

    @Test
    void invalidNestedOfferDoesNotCreateADecisionOrPoisonTheCallbackSequence() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode decision = decision("APPROVED");
        ObjectNode terms = offer();
        terms.put("tenureMonths", 121);
        decision.set("offer", terms);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("DECISION", decision), jwt, "trace"));
        assertEquals(400, failure.getStatusCode().value());
        assertEquals("UNDER_REVIEW", application.getStatus());
        assertEquals(0, application.getSequence());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void validApprovalProducesOrderedDecisionAndOfferCallbacksWithOneTrace() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode decision = decision("APPROVED");
        decision.set("offer", offer());
        simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("DECISION", decision), jwt, "one-trace");
        assertEquals("APPROVED", application.getStatus());
        assertEquals(2, callbacks.size());
        assertEquals(1, callbacks.get(0).getSequence());
        assertEquals(2, callbacks.get(1).getSequence());
        JsonNode first = platform.read(callbacks.get(0).getPayload(), JsonNode.class);
        JsonNode second = platform.read(callbacks.get(1).getPayload(), JsonNode.class);
        assertEquals("lender.decision.received", first.path("eventType").asText());
        assertEquals("loan.offer.created", second.path("eventType").asText());
        assertEquals("one-trace", first.path("correlationId").asText());
        assertEquals("one-trace", second.path("correlationId").asText());
        assertTrue(first.path("data").hasNonNull("decidedAt"));
    }

    @Test
    void informationRequiredDecisionProducesStructuredQuestionsAtomically() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode decision = decision("ACTION_REQUIRED");
        decision.put("informationRequestId", UUID.randomUUID().toString());
        decision.set("questions", platform.json(List.of(Map.of("questionId", "Q1", "prompt", "Explain synthetic variation"))));
        simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("DECISION", decision), jwt, "one-trace");
        assertEquals("ACTION_REQUIRED", application.getStatus());
        assertEquals(2, callbacks.size());
        assertEquals("lender.information.requested", platform.read(callbacks.get(1).getPayload(), JsonNode.class).path("eventType").asText());
        assertEquals(1, platform.read(application.getStateJson(), JsonNode.class).path("informationRequests").size());
    }

    @Test
    void moneyAndTenureTypesMatchIntegrationContract() {
        SimCase application = application();
        externalLookup(application);
        for (String field : List.of("offeredAmount", "feesAmount", "annualInterestRatePercent")) {
            ObjectNode terms = offer();
            terms.put(field, 1.5);
            assertThrows(ResponseStatusException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("CREATE_OFFER", terms), jwt, "trace"));
        }
        ObjectNode terms = offer();
        terms.put("tenureMonths", 12.5);
        assertThrows(ResponseStatusException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("CREATE_OFFER", terms), jwt, "trace"));
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void alreadyExpiredOfferIsRejectedBeforeACallbackIsCreated() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode terms = offer();
        terms.put("expiresAt", Instant.now().minusSeconds(1).toString());
        assertThrows(ResponseStatusException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("CREATE_OFFER", terms), jwt, "trace"));
        assertEquals("UNDER_REVIEW", application.getStatus());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void acceptanceUsesTheCommittedTimeEvenWhenTransportArrivesAfterExpiry() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode terms = offer();
        terms.put("status", "ACTIVE");
        terms.put("expiresAt", Instant.now().minusSeconds(10).toString());
        ObjectNode state = (ObjectNode) platform.read(application.getStateJson(), JsonNode.class);
        state.withObject("/offers").set(terms.path("offerId").asText(), terms);
        application.setStateJson(platform.write(state));
        application.setStatus("APPROVED");
        ObjectNode accepted = platform.json(Map.of("offerId", terms.path("offerId").asText(), "acceptanceId", UUID.randomUUID(), "expectedOfferVersion", 0, "acceptedAt", Instant.now().minusSeconds(20).toString())).deepCopy();
        simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("ACCEPT_OFFER", accepted), jwt, "trace");
        assertEquals("OFFER_ACCEPTED", application.getStatus());
        assertEquals(accepted.path("acceptedAt"), platform.read(callbacks.getFirst().getPayload(), JsonNode.class).path("data").path("acceptedAt"));
    }

    @Test
    void anAcceptanceAfterExpiryCannotChangeTheStoredOffer() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode terms = offer();
        terms.put("status", "ACTIVE");
        terms.put("expiresAt", Instant.now().minusSeconds(10).toString());
        ObjectNode state = (ObjectNode) platform.read(application.getStateJson(), JsonNode.class);
        state.withObject("/offers").set(terms.path("offerId").asText(), terms);
        application.setStateJson(platform.write(state));
        application.setStatus("APPROVED");
        String before = application.getStateJson();
        JsonNode accepted = platform.json(Map.of("offerId", terms.path("offerId").asText(), "acceptanceId", UUID.randomUUID(), "expectedOfferVersion", 0, "acceptedAt", Instant.now().toString()));
        assertThrows(ResponseStatusException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("ACCEPT_OFFER", accepted), jwt, "trace"));
        assertEquals("APPROVED", application.getStatus());
        assertEquals(before, application.getStateJson());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void callbackRetryReusesThePersistedEventAndOriginalCorrelationHeader() {
        SimCase application = application();
        externalLookup(application);
        simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("DECISION", decision("APPROVED")), jwt, "original-trace");
        SimCallback callback = callbacks.getFirst();
        callback.setNextAttempt(Instant.now().minusSeconds(1));
        TypedQuery<UUID> ids = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(UUID.class))).thenReturn(ids);
        when(ids.setParameter(anyString(), any())).thenReturn(ids);
        when(ids.setMaxResults(anyInt())).thenReturn(ids);
        when(ids.getResultList()).thenReturn(List.of(callback.getId()));
        TypedQuery<Long> earlier = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(earlier);
        when(earlier.setParameter(anyString(), any())).thenReturn(earlier);
        when(earlier.getSingleResult()).thenReturn(0L);
        when(em.find(SimCallback.class, callback.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(callback);
        JsonNode response = platform.json(Map.of());
        doThrow(new IllegalStateException("SYNTHETIC_TIMEOUT")).doReturn(response).when(platform).call(eq("nbfc-b-callback"), eq(HttpMethod.POST), anyString(), any(), eq(callback.getId().toString()), eq("original-trace"));
        String persisted = callback.getPayload();
        simulator.callbacks();
        assertEquals("PENDING", callback.getStatus());
        callback.setNextAttempt(Instant.now().minusSeconds(1));
        simulator.callbacks();
        assertEquals("SENT", callback.getStatus());
        assertEquals(persisted, callback.getPayload());
        JsonNode expected = platform.read(persisted, JsonNode.class);
        verify(platform, times(2)).call(eq("nbfc-b-callback"), eq(HttpMethod.POST), anyString(), eq(expected), eq(callback.getId().toString()), eq("original-trace"));
    }

    @Test
    void legacyAnswerContractIsRejectedAndUnknownQuestionFieldsAreNotEchoed() {
        SimCase application = application();
        externalLookup(application);
        ObjectNode answer = platform.json(Map.of("informationRequestId", UUID.randomUUID())).deepCopy();
        answer.putArray("answers").addObject().put("questionId", "Q1").put("answer", "Legacy");
        assertThrows(IllegalArgumentException.class, () -> simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("ANSWER_INFORMATION", answer), jwt, "trace"));
        assertTrue(callbacks.isEmpty());
        ObjectNode question = platform.json(Map.of("informationRequestId", UUID.randomUUID(), "unknown", "discard")).deepCopy();
        question.putArray("questions").addObject().put("questionId", "Q1").put("prompt", "Synthetic explanation").put("unknown", "discard");
        simulator.command("nbfc-b", application.getExternalId(), UUID.randomUUID(), envelope("REQUEST_INFORMATION", question), jwt, "trace");
        JsonNode emitted = platform.read(callbacks.getFirst().getPayload(), JsonNode.class).path("data");
        assertFalse(emitted.has("unknown"));
        assertFalse(emitted.path("questions").get(0).has("unknown"));
    }

    @Test
    void aCorrelationHeaderOutsideTheEventContractCannotCreateASubmission() {
        ObjectNode request = request();
        assertThrows(ResponseStatusException.class, () -> simulator.submit("nbfc-b", request, jwt, "spaces are invalid"));
        assertThrows(ResponseStatusException.class, () -> simulator.submit("nbfc-b", request, jwt, "slash/invalid"));
        verify(em, never()).persist(any());
    }
}
