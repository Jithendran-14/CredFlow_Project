package com.credflow.service;

import com.credflow.entity.IntegrationCallback;
import com.credflow.entity.IntegrationDispatch;
import com.credflow.entity.IntegrationTask;
import com.credflow.platform.Platform.Actor;
import com.credflow.platform.Platform.Event;
import com.credflow.platform.Platform;
import com.credflow.repository.IntegrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationBehaviorTest {

    private Platform p;

    private EntityManager em;

    private LenderIntegrationService engine;

    private final Actor seller = new Actor("HUMAN", "seller-123", null);

    @BeforeEach
    void setup() {
        p = spy(new Platform(new MockEnvironment().withProperty("credflow.service-name", "lender-integration-service"), new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class)));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get()).when(p).tx(any());
        doReturn(null).when(p).emit(anyString(), any(UUID.class), anyLong(), any(), any(Actor.class), nullable(Actor.class), nullable(Event.class));
        em = mock(EntityManager.class);
        engine = new LenderIntegrationService(p, new IntegrationRepository(em));
        engine.bankA = "11111111-1111-4111-8111-111111111111";
        engine.nbfcB = "22222222-2222-4222-8222-222222222222";
        engine.bankCallback = "mock-bank-a";
        engine.nbfcCallback = "mock-nbfc-b";
        engine.bankAUrl = "http://localhost:8091";
        engine.nbfcBUrl = "http://localhost:8092";
        engine.orchestrationUrl = "http://localhost:8084";
    }

    private ObjectNode submission() {
        ObjectNode request = p.json(Map.of()).deepCopy();
        for (String field : List.of("submissionId", "applicationId", "productId", "financialSnapshotId", "consentId", "eligibilityResultId", "verificationId")) {
            request.put(field, UUID.randomUUID().toString());
        }
        request.put("lenderId", engine.bankA);
        request.put("applicationRevision", 1);
        request.put("consentVersion", 1);
        request.put("sellerSubject", seller.subject());
        request.put("marketplaceSellerId", "MKT-123");
        request.put("registeredBusinessName", "Synthetic Shop");
        request.put("loanPurpose", "WORKING_CAPITAL");
        request.put("requestedAmount", "1000.00");
        request.put("currency", "INR");
        request.set("originActor", p.json(seller));
        return request;
    }

    private IntegrationDispatch dispatch(JsonNode request) {
        IntegrationDispatch dispatch = new IntegrationDispatch();
        dispatch.setSubmissionId(LenderIntegrationService.uuid(request, "submissionId"));
        dispatch.setApplicationId(LenderIntegrationService.uuid(request, "applicationId"));
        dispatch.setApplicationRevision(request.path("applicationRevision").asInt());
        dispatch.setLenderId(request.path("lenderId").asText());
        dispatch.setPayloadHash(p.hash(request));
        dispatch.setRequestJson(p.write(request));
        return dispatch;
    }

    @Test
    void trustedStringSellerIdDoesNotRequireAnInventedJwtSubject() {
        ObjectNode request = submission();
        request.remove(List.of("sellerSubject", "marketplaceSellerId"));
        request.put("sellerId", "SELLER-1001");
        assertDoesNotThrow(() -> engine.validateSubmission(request));
        IntegrationDispatch dispatch = dispatch(request);
        ObjectNode bankWire = engine.wireRequest(dispatch);
        assertFalse(bankWire.has("sellerSubject"));
        assertEquals("SELLER-1001", bankWire.path("sellerReference").asText());
        dispatch.setLenderId(engine.nbfcB);
        ObjectNode nbfcWire = engine.wireRequest(dispatch);
        assertFalse(nbfcWire.has("sellerSubject"));
        assertEquals("SELLER-1001", nbfcWire.path("merchantCode").asText());
        request.remove("sellerId");
        assertThrows(IllegalArgumentException.class, () -> engine.validateSubmission(request));
    }

    private ObjectNode callback(IntegrationDispatch dispatch) {
        ObjectNode body = p.json(Map.of()).deepCopy();
        body.put("eventId", UUID.randomUUID().toString());
        body.put("submissionId", dispatch.getSubmissionId().toString());
        body.put("applicationId", dispatch.getApplicationId().toString());
        body.put("applicationRevision", dispatch.getApplicationRevision());
        body.put("lenderId", dispatch.getLenderId());
        body.put("lenderApplicationId", "BA-123");
        body.put("lenderSequence", 1);
        body.put("eventType", "lender.information.answered");
        body.put("occurredAt", Instant.now().toString());
        body.set("data", p.json(Map.of("informationRequestId", UUID.randomUUID().toString())));
        return body;
    }

    @Test
    void anotherLenderCannotOccupyTheSubmissionsCallbackSequence() {
        IntegrationDispatch dispatch = dispatch(submission());
        dispatch.setLenderId(engine.nbfcB);
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        ObjectNode body = callback(dispatch);
        body.put("lenderId", engine.bankA);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> engine.callback(engine.bankA, body, new Actor("SYSTEM_CLIENT", "mock-bank-a", engine.bankA), "callback-test"));
        assertEquals(409, failure.getStatusCode().value());
        verify(em, never()).persist(any());
        verify(em, never()).createQuery(anyString(), eq(IntegrationCallback.class));
    }

    @Test
    void callbackBeforeReservationIsRetryableAndDoesNotOccupyASequence() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode body = callback(dispatch);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> engine.callback(engine.bankA, body, seller, "callback-test"));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals("SUBMISSION_NOT_RESERVED_RETRY", failure.getReason());
        verify(em, never()).persist(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void correctlyBoundCallbackCanWaitForTheLostReceipt() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode body = callback(dispatch);
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        TypedQuery<IntegrationCallback> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(IntegrationCallback.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        engine.callback(engine.bankA, body, seller, "callback-test");
        var captured = org.mockito.ArgumentCaptor.forClass(IntegrationCallback.class);
        verify(em).persist(captured.capture());
        assertEquals("PENDING", captured.getValue().getStatus());
        assertEquals(dispatch.getSubmissionId(), captured.getValue().getSubmissionId());
        assertNull(dispatch.getExternalId());
    }

    @Test
    void delayedReservationAcknowledgesCancellationWithoutCreatingASubmitTask() {
        ObjectNode request = submission();
        IntegrationDispatch dispatch = dispatch(request);
        dispatch.setCancellationRequested(true);
        dispatch.setCancellationAcknowledged(true);
        dispatch.setStatus("CANCELLED");
        JsonNode cancellation = p.json(Map.of("cancellationId", UUID.randomUUID(), "applicationId", dispatch.getApplicationId(), "submissionId", dispatch.getSubmissionId(), "applicationRevision", dispatch.getApplicationRevision(), "lenderId", dispatch.getLenderId(), "originActor", seller));
        dispatch.setRequestJson(p.write(cancellation));
        dispatch.setPayloadHash(p.hash(cancellation));
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        JsonNode first = engine.reserve(request);
        JsonNode replay = engine.reserve(request);
        assertEquals("CANCELLED", first.path("status").asText());
        assertEquals(first, replay);
        assertTrue(dispatch.isCancellationRequested());
        assertTrue(dispatch.isCancellationAcknowledged());
        assertEquals(p.hash(request), dispatch.getPayloadHash());
        assertTrue(p.read(dispatch.getRequestJson(), JsonNode.class).hasNonNull("financialSnapshotId"));
        verify(em, never()).persist(any());
        ObjectNode changed = request.deepCopy();
        changed.put("requestedAmount", "2000.00");
        assertThrows(ResponseStatusException.class, () -> engine.reserve(changed));
    }

    @Test
    void cancellationPlaceholderCannotBeClaimedByAnotherApplication() {
        ObjectNode request = submission();
        IntegrationDispatch dispatch = dispatch(request);
        dispatch.setCancellationRequested(true);
        dispatch.setStatus("CANCELLED");
        dispatch.setRequestJson(p.write(Map.of("cancellationId", UUID.randomUUID())));
        String originalHash = dispatch.getPayloadHash();
        ObjectNode otherApplication = request.deepCopy();
        otherApplication.put("applicationId", UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class, () -> engine.existingReservation(dispatch, otherApplication, p.hash(otherApplication)));
        assertEquals(originalHash, dispatch.getPayloadHash());
    }

    private ObjectNode authorization(IntegrationDispatch dispatch) {
        JsonNode original = p.read(dispatch.getRequestJson(), JsonNode.class);
        return p.json(Map.of("authorized", true, "applicationId", dispatch.getApplicationId(), "applicationRevision", dispatch.getApplicationRevision(), "consentId", original.path("consentId").asText(), "consentVersion", original.path("consentVersion").asInt())).deepCopy();
    }

    @Test
    void expiredPermissionPausesQueuedSubmissionWithoutCancellingOrSharing() {
        IntegrationDispatch dispatch=dispatch(submission());
        when(em.find(IntegrationDispatch.class,dispatch.getSubmissionId())).thenReturn(dispatch);
        var spied=spy(engine);doReturn(null).when(spied).lookup(dispatch);
        var denied=authorization(dispatch);denied.put("authorized",false);denied.put("closed",false);
        doReturn(denied).when(p).call(eq("orchestration"),eq(HttpMethod.GET),anyString(),isNull(),isNull(),anyString());
        IntegrationTask task=new IntegrationTask();task.setTaskId(UUID.randomUUID());task.setSubmissionId(dispatch.getSubmissionId());
        assertThrows(LenderIntegrationService.ConsentPaused.class,()->spied.sendSubmission(task));
        verify(p,never()).call(eq("simulator"),eq(HttpMethod.POST),anyString(),any(),anyString(),anyString());
    }
    @Test
    void consentPauseDoesNotConsumeDeliveryRetryBudgetOrEmitFailureNoise() {
        IntegrationDispatch dispatch=dispatch(submission());UUID lease=UUID.randomUUID();
        IntegrationTask task=new IntegrationTask();task.setTaskId(UUID.randomUUID());task.setSubmissionId(dispatch.getSubmissionId());
        task.setLeaseToken(lease);task.setAttempts(12);task.setOperation("SUBMIT");dispatch.setLeaseToken(lease);
        when(em.find(IntegrationDispatch.class,dispatch.getSubmissionId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        when(em.find(IntegrationTask.class,task.getTaskId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(task);
        engine.finish(task,new LenderIntegrationService.ConsentPaused(),false);
        assertEquals("PENDING",task.getStatus());assertEquals(11,task.getAttempts());assertNull(dispatch.getLeaseToken());
        verify(p,never()).emit(anyString(),any(),anyLong(),any(),any(),any(),any());
    }
    @Test
    void manualDecisionMustContainSpecificLenderQuestions() {
        ObjectNode data=p.json(Map.of("decisionId",UUID.randomUUID(),"decision","MANUAL_REVIEW")).deepCopy();
        assertThrows(IllegalArgumentException.class,()->engine.validateCommand("DECISION",data));
        data.put("informationRequestId",UUID.randomUUID().toString());
        data.putArray("questions").addObject().put("questionId","stock").put("prompt","Confirm the inventory value.");
        assertDoesNotThrow(()->engine.validateCommand("DECISION",data));
    }

    @Test
    void wrongAuthorizationCannotTriggerALenderCreate() {
        IntegrationDispatch dispatch = dispatch(submission());
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId())).thenReturn(dispatch);
        LenderIntegrationService spied = spy(engine);
        doReturn(null).when(spied).lookup(dispatch);
        ObjectNode wrong = authorization(dispatch);
        wrong.put("applicationId", UUID.randomUUID().toString());
        doReturn(wrong).when(p).call(eq("orchestration"), eq(HttpMethod.GET), anyString(), isNull(), isNull(), anyString());
        IntegrationTask task = new IntegrationTask();
        task.setTaskId(UUID.randomUUID());
        task.setSubmissionId(dispatch.getSubmissionId());
        assertThrows(IllegalStateException.class, () -> spied.sendSubmission(task));
        verify(p, never()).call(eq("simulator"), eq(HttpMethod.POST), anyString(), any(), anyString(), anyString());
    }

    @Test
    void authorizationRequiresAnExplicitBooleanAndCurrentConsentBinding() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode valid = authorization(dispatch);
        assertDoesNotThrow(() -> engine.validateAuthorization(dispatch, valid));
        ObjectNode missing = valid.deepCopy();
        missing.remove("authorized");
        assertThrows(IllegalStateException.class, () -> engine.validateAuthorization(dispatch, missing));
        ObjectNode wrongRevision = valid.deepCopy();
        wrongRevision.put("applicationRevision", 2);
        assertThrows(IllegalStateException.class, () -> engine.validateAuthorization(dispatch, wrongRevision));
        ObjectNode changedConsent = valid.deepCopy();
        changedConsent.put("consentVersion", 2);
        assertThrows(IllegalStateException.class, () -> engine.validateAuthorization(dispatch, changedConsent));
        changedConsent.put("authorized", false);
        assertDoesNotThrow(() -> engine.validateAuthorization(dispatch, changedConsent));
    }

    @Test
    void deadBoundCallbackPublishesAWorkflowHealthFailure() {
        IntegrationDispatch dispatch = dispatch(submission());
        dispatch.setExternalId("BA-123");
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        ObjectNode body = callback(dispatch);
        IntegrationCallback callback = new IntegrationCallback();
        callback.setEventId(UUID.fromString(body.path("eventId").asText()));
        callback.setSubmissionId(dispatch.getSubmissionId());
        callback.setCallbackJson(p.write(body));
        engine.markCallbackDead(callback, "CALLBACK_PREREQUISITE_MISSING");
        assertEquals("DEAD", callback.getStatus());
        assertEquals(1, dispatch.getEventVersion());
        var captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(p).emit(eq("lender.processing.failed"), eq(dispatch.getApplicationId()), eq(1L), captured.capture(), any(Actor.class), eq(seller), any(Event.class));
        JsonNode emitted = p.json(captured.getValue());
        assertEquals("CALLBACK", emitted.path("operation").asText());
        assertEquals("MANUAL_INTERVENTION_REQUIRED", emitted.path("processingHealth").asText());
        assertEquals(callback.getEventId().toString(), emitted.path("taskId").asText());
    }

    private ObjectNode offer() {
        return p.json(Map.ofEntries(Map.entry("offerId", UUID.randomUUID().toString()), Map.entry("offeredAmount", "900.00"), Map.entry("feesAmount", "0.00"), Map.entry("currency", "INR"), Map.entry("annualInterestRatePercent", "12.5"), Map.entry("interestRateType", "FIXED"), Map.entry("repaymentFrequency", "MONTHLY"), Map.entry("tenureMonths", 12), Map.entry("termsReference", "synthetic/terms/v1"), Map.entry("expiresAt", Instant.now().plusSeconds(3600).toString()), Map.entry("version", 0))).deepCopy();
    }

    @Test
    void nestedApprovalOfferIsValidatedBeforeACommandCanBePersisted() {
        ObjectNode decision = p.json(Map.of("decisionId", UUID.randomUUID(), "decision", "APPROVED")).deepCopy();
        ObjectNode terms = offer();
        decision.set("offer", terms);
        assertDoesNotThrow(() -> engine.validateCommand("DECISION", decision));
        terms.put("tenureMonths", 121);
        assertThrows(IllegalArgumentException.class, () -> engine.command(UUID.randomUUID(), UUID.randomUUID(), "DECISION", decision, seller));
        verify(em, never()).persist(any());
        terms.put("tenureMonths", 12.5);
        assertThrows(IllegalArgumentException.class, () -> engine.validateCommand("DECISION", decision));
        terms.put("tenureMonths", 12);
        terms.put("offeredAmount", 900);
        assertThrows(IllegalArgumentException.class, () -> engine.validateCommand("DECISION", decision));
    }

    @Test
    void decisionTimeAndTraceAreStoredOnceAndReusedForIdempotentRetry() {
        UUID taskId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        ObjectNode decision = p.json(Map.of("decisionId", UUID.randomUUID(), "decision", "MANUAL_REVIEW")).deepCopy();
        ObjectNode first = engine.commandEnvelope("DECISION", engine.traced(decision, "original-trace"), seller);
        jakarta.persistence.Query sequence = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(sequence);
        when(sequence.getSingleResult()).thenReturn(1L);
        engine.saveTask(taskId, submissionId, "DECISION", first);
        var captured = org.mockito.ArgumentCaptor.forClass(IntegrationTask.class);
        verify(em).persist(captured.capture());
        IntegrationTask stored = captured.getValue();
        JsonNode persisted = engine.parse(stored.getPayloadJson());
        Instant decidedAt = Instant.parse(persisted.path("data").path("decidedAt").asText());
        assertFalse(decidedAt.isAfter(Instant.now()));
        assertEquals("original-trace", persisted.path("data").path("correlationId").asText());
        when(em.find(IntegrationTask.class, taskId)).thenReturn(stored);
        decision.put("decidedAt", "2099-01-01T00:00:00Z");
        ObjectNode retry = engine.commandEnvelope("DECISION", engine.traced(decision, "different-retry-trace"), seller);
        engine.saveTask(taskId, submissionId, "DECISION", retry);
        verify(em, times(1)).persist(any());
        assertEquals(persisted, engine.parse(stored.getPayloadJson()));
        decision.put("decision", "REJECTED");
        decision.put("reasonCode", "SYNTHETIC_REASON");
        assertThrows(ResponseStatusException.class, () -> engine.saveTask(taskId, submissionId, "DECISION", engine.commandEnvelope("DECISION", decision, seller)));
    }

    @Test
    void publicTraceBodyCannotOverrideAuthenticatedHeader() {
        ObjectNode body = p.json(Map.of("correlationId", "body-value")).deepCopy();
        assertEquals("header-value", engine.traced(body, "header-value").path("correlationId").asText());
        assertEquals("body-value", body.path("correlationId").asText());
        assertThrows(IllegalArgumentException.class, () -> engine.traced(body, "bad\r\nheader"));
        assertThrows(IllegalArgumentException.class, () -> engine.traced(body, "spaces are invalid"));
        assertThrows(IllegalArgumentException.class, () -> engine.traced(body, "slash/invalid"));
    }

    @Test
    void lateCallbackWaitsForItsSequenceAndDuplicateDoesNotEmitAgain() {
        IntegrationDispatch dispatch = dispatch(submission());
        dispatch.setExternalId("BA-123");
        ObjectNode body = callback(dispatch);
        body.put("lenderSequence", 2);
        IntegrationCallback row = new IntegrationCallback();
        row.setEventId(UUID.fromString(body.path("eventId").asText()));
        row.setSubmissionId(dispatch.getSubmissionId());
        row.setLenderSequence(2);
        row.setCallbackJson(p.write(body));
        row.setActorJson(p.write(p.systemActor()));
        row.setCorrelationId("journey-trace");
        when(em.find(IntegrationCallback.class, row.getEventId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(row);
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        engine.applyCallback(row.getEventId());
        assertEquals("PENDING", row.getStatus());
        verify(p, never()).emit(anyString(), any(), anyLong(), any(), any(), any(), any());
        dispatch.setCallbackSequence(1);
        engine.applyCallback(row.getEventId());
        engine.applyCallback(row.getEventId());
        assertEquals("APPLIED", row.getStatus());
        assertEquals(2, dispatch.getCallbackSequence());
        verify(p, times(1)).emit(eq("lender.information.answered"), eq(dispatch.getApplicationId()), eq(1L), any(), any(), any(), argThat(cause -> cause != null && "journey-trace".equals(cause.correlationId()) && row.getEventId().equals(cause.eventId())));
    }

    @Test
    void lookupAfterLostReceiptReconcilesWithoutCreatingAnotherApplication() {
        ObjectNode request = submission();
        request.put("correlationId", "one-journey");
        IntegrationDispatch dispatch = dispatch(request);
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId())).thenReturn(dispatch);
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        JsonNode receipt = p.json(Map.of("bankApplicationId", "BA-123", "submissionReference", dispatch.getSubmissionId(), "acceptedAt", Instant.now().toString()));
        doReturn(receipt).when(p).call(eq("simulator"), eq(HttpMethod.GET), anyString(), isNull(), isNull(), eq("one-journey"));
        IntegrationTask task = new IntegrationTask();
        task.setTaskId(dispatch.getSubmissionId());
        task.setSubmissionId(dispatch.getSubmissionId());
        engine.sendSubmission(task);
        assertEquals("BA-123", dispatch.getExternalId());
        assertEquals("SUBMITTED", dispatch.getStatus());
        verify(p, never()).call(anyString(), eq(HttpMethod.POST), anyString(), any(), any(), any());
        verify(p).emit(eq("lender.application.submitted"), any(), anyLong(), any(), any(), any(), argThat(cause -> "one-journey".equals(cause.correlationId())));
    }

    @Test
    void successfulRetryRecoversOnlyTheFailedTaskAndPreservesCause() {
        ObjectNode request = submission();
        IntegrationDispatch dispatch = dispatch(request);
        dispatch.setExternalId("BA-123");
        IntegrationTask task = new IntegrationTask();
        task.setTaskId(UUID.randomUUID());
        task.setSubmissionId(dispatch.getSubmissionId());
        task.setOperation("ANSWER_INFORMATION");
        task.setStatus("WORKING");
        task.setAttempts(2);
        task.setFailureCode("ResourceAccessException");
        task.setLeaseToken(UUID.randomUUID());
        UUID informationId = UUID.randomUUID();
        ObjectNode data = p.json(Map.of("informationRequestId", informationId, "correlationId", "stable-trace")).deepCopy();
        task.setPayloadJson(p.write(engine.commandEnvelope(task.getOperation(), data, seller)));
        dispatch.setLeaseToken(task.getLeaseToken());
        when(em.find(IntegrationDispatch.class, dispatch.getSubmissionId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(dispatch);
        when(em.find(IntegrationTask.class, task.getTaskId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(task);
        engine.finish(task, null, false);
        assertEquals("DONE", task.getStatus());
        assertNull(task.getFailureCode());
        var captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(p).emit(eq("lender.processing.recovered"), eq(dispatch.getApplicationId()), eq(1L), captured.capture(), any(), eq(seller), argThat(cause -> "stable-trace".equals(cause.correlationId())));
        JsonNode recovered = p.json(captured.getValue());
        assertEquals(task.getTaskId().toString(), recovered.path("taskId").asText());
        assertEquals(informationId.toString(), recovered.path("informationRequestId").asText());
        assertEquals(task.getOperation(), recovered.path("operation").asText());
    }

    @Test
    void informationRequiredDecisionNeedsStructuredQuestions() {
        ObjectNode decision = p.json(Map.of("decisionId", UUID.randomUUID(), "decision", "ACTION_REQUIRED")).deepCopy();
        assertThrows(IllegalArgumentException.class, () -> engine.validateCommand("DECISION", decision));
        decision.put("informationRequestId", UUID.randomUUID().toString());
        decision.set("questions", p.json(List.of(Map.of("questionId", "Q1", "prompt", "Synthetic explanation"))));
        assertDoesNotThrow(() -> engine.validateCommand("DECISION", decision));
    }

    @Test
    void callbackOriginMustMatchItsExactCommandAndResource() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode body = callback(dispatch);
        UUID taskId = UUID.randomUUID();
        Actor expected = new Actor("HUMAN", "officer-current", engine.bankA);
        Actor earlier = new Actor("HUMAN", "officer-earlier", engine.bankA);
        IntegrationTask task = new IntegrationTask();
        task.setTaskId(taskId);
        task.setSubmissionId(dispatch.getSubmissionId());
        task.setOperation("ANSWER_INFORMATION");
        task.setPayloadJson(p.write(engine.commandEnvelope(task.getOperation(), body.path("data"), expected)));
        when(em.find(IntegrationTask.class, taskId)).thenReturn(task);
        body.put("causationId", taskId.toString());
        body.set("originActor", p.json(earlier));
        assertThrows(IllegalArgumentException.class, () -> engine.trustedOrigin(body, dispatch));
        body.set("originActor", p.json(expected));
        assertEquals(expected, engine.trustedOrigin(body, dispatch));
        ((ObjectNode) body.path("data")).put("informationRequestId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> engine.trustedOrigin(body, dispatch));
        verify(em, never()).createQuery(anyString(), eq(String.class));
    }

    @Test
    void aManualReviewCommandCannotBeReusedAsAnApprovalOrChangeItsDecisionTime() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode body = callback(dispatch);
        IntegrationTask task = new IntegrationTask();
        task.setTaskId(UUID.randomUUID());
        task.setSubmissionId(dispatch.getSubmissionId());
        task.setOperation("DECISION");
        ObjectNode decision = p.json(Map.of("decisionId", UUID.randomUUID(), "decision", "MANUAL_REVIEW", "decidedAt", Instant.now().toString())).deepCopy();
        task.setPayloadJson(p.write(engine.commandEnvelope(task.getOperation(), decision, seller)));
        when(em.find(IntegrationTask.class, task.getTaskId())).thenReturn(task);
        body.put("causationId", task.getTaskId().toString());
        body.put("eventType", "loan.offer.created");
        body.set("data", offer());
        assertThrows(IllegalArgumentException.class, () -> engine.trustedOrigin(body, dispatch));
        body.put("eventType", "lender.decision.received");
        ObjectNode altered = decision.deepCopy();
        altered.put("decidedAt", Instant.now().minusSeconds(60).toString());
        body.set("data", altered);
        assertThrows(IllegalArgumentException.class, () -> engine.trustedOrigin(body, dispatch));
        body.set("data", decision);
        assertEquals(seller, engine.trustedOrigin(body, dispatch));
        decision.put("decision", "APPROVED");
        ObjectNode terms = offer();
        decision.set("offer", terms);
        task.setPayloadJson(p.write(engine.commandEnvelope(task.getOperation(), decision, seller)));
        ObjectNode normalized = terms.deepCopy();
        normalized.put("annualInterestRatePercent", "12.5000");
        body.put("eventType", "loan.offer.created");
        body.set("data", normalized);
        assertEquals(seller, engine.trustedOrigin(body, dispatch));
        normalized.put("feesAmount", "10.00");
        assertThrows(IllegalArgumentException.class, () -> engine.trustedOrigin(body, dispatch));
    }

    @Test
    void legacyAnswerFieldsAndOversizedTermsAreRejectedBeforePersistence() {
        ObjectNode command = p.json(Map.of("informationRequestId", UUID.randomUUID())).deepCopy();
        ObjectNode answer = command.putArray("answers").addObject().put("questionId", "Q1").put("answer", "Legacy value");
        assertThrows(IllegalArgumentException.class, () -> engine.validateCommand("ANSWER_INFORMATION", command));
        answer.remove("answer");
        answer.put("answerText", "Canonical answer");
        answer.putArray("documentReferences").add("https://real-doc.invalid/private");
        assertThrows(IllegalArgumentException.class, () -> engine.validateCommand("ANSWER_INFORMATION", command));
        ObjectNode terms = offer();
        terms.put("termsReference", "x".repeat(256));
        assertThrows(IllegalArgumentException.class, () -> engine.validateOffer(terms));
        verify(em, never()).persist(any());
    }

    @Test
    void invalidLoanPurposeAndOversizedBusinessNameCannotBeReserved() {
        ObjectNode request = submission();
        request.put("loanPurpose", "UNAGREED_PURPOSE");
        assertThrows(IllegalArgumentException.class, () -> engine.reserve(request));
        request.put("loanPurpose", "WORKING_CAPITAL");
        request.put("registeredBusinessName", "x".repeat(256));
        assertThrows(IllegalArgumentException.class, () -> engine.reserve(request));
        verify(em, never()).persist(any());
    }

    @Test
    void malformedOptionalCallbackFieldsCannotOccupyASequence() {
        IntegrationDispatch dispatch = dispatch(submission());
        ObjectNode body = callback(dispatch);
        ObjectNode payload = (ObjectNode) body.path("data");
        payload.putArray("answers").addObject().put("questionId", "Q1");
        assertThrows(IllegalArgumentException.class, () -> engine.callback(engine.bankA, body, seller, "trace"));
        payload.remove("answers");
        payload.putArray("syntheticDocumentReferences").add("https://real-document.invalid/private");
        assertThrows(IllegalArgumentException.class, () -> engine.validateCallback(engine.bankA, body));
        payload.remove("syntheticDocumentReferences");
        for (String timestamp : List.of("answeredAt", "createdAt", "acceptedAt", "operationAt", "decidedAt")) {
            payload.put(timestamp, "not-an-instant");
            assertThrows(java.time.format.DateTimeParseException.class, () -> engine.validateCallback(engine.bankA, body));
            payload.put(timestamp, Instant.now().plusSeconds(600).toString());
            assertThrows(IllegalArgumentException.class, () -> engine.validateCallback(engine.bankA, body));
            payload.remove(timestamp);
        }
        payload.put("decision", 123);
        assertThrows(IllegalArgumentException.class, () -> engine.validateCallback(engine.bankA, body));
        payload.remove("decision");
        payload.put("expectedOfferVersion", 0.5);
        assertThrows(IllegalArgumentException.class, () -> engine.validateCallback(engine.bankA, body));
        payload.remove("expectedOfferVersion");
        payload.putArray("answers").addObject().put("questionId", "Q1").put("answerText", "Canonical synthetic answer");
        payload.putArray("syntheticDocumentReferences").add("synthetic://demo/inventory");
        payload.put("answeredAt", Instant.now().toString());
        assertDoesNotThrow(() -> engine.validateCallback(engine.bankA, body));
        verify(em, never()).persist(any());
    }
}
