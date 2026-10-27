package com.credflow.service;

import com.credflow.dto.OfferActionRequest;
import com.credflow.dto.WorkflowReply;
import com.credflow.entity.IdempotencyRow;
import com.credflow.entity.InformationRow;
import com.credflow.entity.OfferRow;
import com.credflow.entity.SubmissionRow;
import com.credflow.entity.WorkflowRow;
import com.credflow.platform.Platform.*;
import com.credflow.platform.Platform;
import com.credflow.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkflowBehaviorTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Platform p = mock(Platform.class);

    private final EntityManager em = mock(EntityManager.class);

    private final Actor actor = new Actor("HUMAN", "seller", null);

    private final WorkflowService service = new WorkflowService(
        new WorkflowRepository(em),
        p,
        "integration",
        "application",
        "consent"
    );

    private void configure(WorkflowRow w) throws Exception {
        when(em.find(eq(WorkflowRow.class), eq(w.getId()), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(w);
        when(p.call(eq("consent"),any(),anyString(),isNull(),isNull(),anyString())).thenAnswer(a -> mapper.valueToTree(Map.of("applicationId",w.getId().toString(),"applicationRevision",w.getRevision(),"status","ACTIVE","operationalConsentVersion",w.getConsentVersion(),"expiresAt",Instant.now().plusSeconds(86400).toString())));
        when(p.json(any())).thenAnswer(a -> mapper.valueToTree(a.getArgument(0)));
        when(p.write(any())).thenAnswer(a -> mapper.writeValueAsString(a.getArgument(0)));
        when(p.read(anyString(), eq(JsonNode.class))).thenAnswer(a -> mapper.readTree((String) a.getArgument(0)));
        when(p.hash(any())).thenReturn("idem-hash");
        when(p.systemActor()).thenReturn(new Actor("SYSTEM", "scheduler", null));
        when(p.actor(any())).thenReturn(actor);
    }

    private WorkflowRow workflow(String state) {
        WorkflowRow w = new WorkflowRow();
        w.setId(UUID.randomUUID());
        w.setStatus(state);
        w.setSellerSubject("seller");
        w.setSellerId("SELLER-1001");
        w.setEvaluationAsOfDate(java.time.LocalDate.of(2026, 10, 13));
        w.setAuthorisedRetrievalReference("authorised-ref-104");
        w.setRevision(1);
        w.setWorkflowVersion(1);
        w.setAmount(new BigDecimal("5000.00"));
        w.setHealth("OK");
        w.setCurrency("INR");
        w.setPurpose("PURCHASE_INVENTORY");
        w.setLegalName("Example shop");
        w.setProfileJson("{\"businessName\":\"Synthetic shop\",\"phase0\":{\"preferredTenureMonths\":12,\"declaredMonthlyIncome\":\"500000.00\",\"monthlyExpenses\":\"100000.00\",\"existingMonthlyDebt\":\"10000.00\",\"dateOfBirth\":\"1990-01-01\",\"documentType\":\"MOCK_ID\",\"documentReference\":\"TEST-IDENTITY001\",\"detailsConfirmed\":true,\"kycTermsAccepted\":true}}");
        w.setMarketplaceSellerId("SELLER-1");
        w.setMarketplaceId(UUID.randomUUID());
        w.setConsentId(UUID.randomUUID());
        w.setConsentVersion(1);
        w.setConsentGranted(true);
        w.setVerificationId(UUID.randomUUID());
        w.setSnapshotId(UUID.randomUUID());
        w.setEvaluationId(UUID.randomUUID());
        return w;
    }

    private Event event(WorkflowRow w, String type, Map<String, Object> data, String producer) {
        return new Event(UUID.randomUUID(), type, "1.0", Instant.now(), producer, w.getId(), 1, "test-correlation", null, mapper.valueToTree(data), new Actor("SYSTEM_CLIENT", producer, null), actor);
    }

    @Test
    void failedEligibilityReceiptPausesWorkflowAndLateReceiptIsIgnored() throws Exception {
        WorkflowRow w=workflow("ELIGIBILITY_CHECKING");configure(w);UUID operation=UUID.randomUUID();w.setEligibilityOperationId(operation);
        service.eligibilityProcessingFailed(new WorkflowService.PendingEligibility(w.getId(),UUID.randomUUID(),1));
        assertEquals("ELIGIBILITY_CHECKING",w.getStatus());
        service.eligibilityProcessingFailed(new WorkflowService.PendingEligibility(w.getId(),operation,2));
        assertEquals("ELIGIBILITY_CHECKING",w.getStatus());
        service.eligibilityProcessingFailed(new WorkflowService.PendingEligibility(w.getId(),operation,1));
        assertEquals("ACTION_REQUIRED",w.getStatus());assertEquals("ELIGIBILITY_PROCESSING_FAILED",w.getDetail());
        assertNull(w.getSubmissionId());long version=w.getWorkflowVersion();
        service.eligibilityProcessingFailed(new WorkflowService.PendingEligibility(w.getId(),operation,1));
        assertEquals(version,w.getWorkflowVersion());
    }

    @Test
    void missingCreditBandPausesWithoutDispatchAndRetainsResultReference() throws Exception {
        WorkflowRow w=workflow("ELIGIBILITY_CHECKING");configure(w);
        Map<String,Object> d=new LinkedHashMap<>();
        d.put("applicationRevision",1);d.put("applicationId",w.getId());
        d.put("asOfDate",w.getEvaluationAsOfDate().toString());d.put("requestedAmount","5000.00");d.put("currency","INR");
        d.put("verificationId",w.getVerificationId());d.put("evaluationRequestId",w.getEvaluationId());
        d.put("financialSnapshotId",w.getSnapshotId());UUID resultId=UUID.randomUUID();d.put("eligibilityResultId",resultId);
        d.put("outcome","ACTION_REQUIRED");d.put("calculationVersion","phase0-2.0");d.put("evaluatedAt",Instant.now().toString());
        d.put("reasonCodes",List.of("CREDIT_INFORMATION_REQUIRED"));d.put("lenderResults",List.of());
        service.onEvent(event(w,"eligibility.completed",d,"eligibility-service"));
        assertEquals("ACTION_REQUIRED",w.getStatus());assertEquals("CREDIT_INFORMATION_REQUIRED",w.getDetail());
        assertEquals(resultId,w.getEligibilityId());assertNull(w.getSubmissionId());
        verify(p,never()).job(any(),eq("integration"),anyString(),any(),anyString(),any(),any());
    }

    @Test
    void ignoresVerificationForAnOldExpectedJob() throws Exception {
        WorkflowRow w = workflow("SUBMITTED");
        configure(w);
        service.onEvent(event(w, "verification.completed", Map.of("applicationRevision", 1, "verificationId", UUID.randomUUID(), "status", "VERIFIED"), "consent-verification-service"));
        assertEquals("SUBMITTED", w.getStatus());
        verify(p, never()).job(any(), anyString(), anyString(), any(), anyString(), any(), any());
        verify(p, never()).emit(anyString(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void routesOnceUsingPriorityThenProductId() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        configure(w);
        String lenderA = "11111111-1111-4111-8111-111111111111", lenderB = "22222222-2222-4222-8222-222222222222";
        String first = "11111111-1111-4111-8111-111111111112", second = "22222222-2222-4222-8222-222222222223";
        w.setConsentJson(mapper.writeValueAsString(Map.of("recipientLenderIds", List.of(lenderA, lenderB))));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("applicationRevision", 1);
        d.put("applicationId", w.getId());
        d.put("asOfDate", w.getEvaluationAsOfDate().toString());
        d.put("requestedAmount", w.getAmount().toPlainString());
        d.put("currency", w.getCurrency());
        d.put("averageMonthlyNetSales", "550000.00");
        d.put("averageMonthlyPayout", "530000.00");
        d.put("returnRatePercent", "8.33");
        d.put("verificationId", w.getVerificationId());
        d.put("financialSnapshotId", w.getSnapshotId());
        d.put("evaluationRequestId", w.getEvaluationId());
        d.put("eligibilityResultId", UUID.randomUUID());
        d.put("outcome", "EVALUATED");
        d.put("calculationVersion", "1.0");
        d.put("evaluatedAt", Instant.now().toString());
        d.put("reasonCodes", List.of());
        d.put("lenderResults", List.of(Map.of("lenderId", lenderB, "productId", second, "routingPriority", 10, "eligible", true, "indicativeMaxAmount", "10000.00", "ruleVersion", "1.0", "reasonCodes", List.of()), Map.of("lenderId", lenderA, "productId", first, "routingPriority", 10, "eligible", true, "indicativeMaxAmount", "10000.00", "ruleVersion", "1.0", "reasonCodes", List.of())));
        Event result = event(w, "eligibility.completed", d, "eligibility-service");
        // Oracle NUMBER may return an integral amount without a decimal scale.
        w.setAmount(w.getAmount().stripTrailingZeros());
        service.onEvent(result);
        service.onEvent(result);
        assertEquals(lenderA, w.getLenderId());
        assertEquals(first, w.getProductId());
        assertEquals("SUBMITTING", w.getStatus());
        verify(p, times(1)).job(any(), eq("integration"), eq("/api/v1/internal/submissions"), argThat(payload -> "5000.00".equals(mapper.valueToTree(payload).path("requestedAmount").asText())), anyString(), eq(w.getId()), eq(actor));
    }

    @Test
    void expiredAcceptanceReturnsConflictAfterRecordingExpiry() throws Exception {
        WorkflowRow w = workflow("APPROVED");
        configure(w);
        OfferRow o = new OfferRow();
        o.setId(UUID.randomUUID());
        o.setApplicationId(w.getId());
        o.setSubmissionId(UUID.randomUUID());
        o.setStatus("ACTIVE");
        o.setExpiresAt(Instant.now().minusSeconds(1));
        o.setVersion(0);
        when(em.find(OfferRow.class, o.getId())).thenReturn(o);
        when(em.find(OfferRow.class, o.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(o);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("seller");
        WorkflowReply result;
        org.slf4j.MDC.put("correlationId", "offer-expiry-trace");
        try {
            result = service.offerAction(o.getId(), new OfferActionRequest(0L), "accept-1", jwt, true);
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
        assertEquals(409, result.status());
        assertEquals("EXPIRED", o.getStatus());
        assertEquals("OFFER_EXPIRED", w.getStatus());
        assertEquals("offer-expiry-trace", result.body().path("correlationId").asText());
        verify(p, never()).emit(eq("loan.offer.accepted"), any(), anyLong(), any(), any(), any(), any());
        var saved = org.mockito.ArgumentCaptor.forClass(IdempotencyRow.class);
        verify(em).persist(saved.capture());
        when(em.find(IdempotencyRow.class, "idem-hash")).thenReturn(saved.getValue());
        WorkflowReply replay = service.offerAction(o.getId(), new OfferActionRequest(0L), "accept-1", jwt, true);
        assertEquals(result, replay);
        assertEquals(1, o.getVersion());
        verify(em, times(1)).persist(any(IdempotencyRow.class));
    }

    @Test
    void waitsForEarlierLenderEventAcrossTopics() throws Exception {
        WorkflowRow w = workflow("SUBMITTING");
        configure(w);
        Event e = new Event(UUID.randomUUID(), "loan.offer.created", "1.0", Instant.now(), "lender-integration-service", w.getId(), 2, "trace", null, mapper.createObjectNode(), actor, actor);
        assertThrows(Platform.Pending.class, () -> service.onEvent(e));
        assertEquals(0, w.getLastIntegrationVersion());
        verify(p, never()).emit(anyString(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void lenderCanRejectWhileQuestionsRemainUnanswered() throws Exception {
        WorkflowRow w = workflow("ACTION_REQUIRED");
        configure(w);
        w.setSubmissionId(UUID.randomUUID());
        w.setLenderId(UUID.randomUUID().toString());
        SubmissionRow s = new SubmissionRow();
        s.setId(w.getSubmissionId());
        s.setApplicationId(w.getId());
        s.setLenderId(w.getLenderId());
        s.setRevision(1);
        s.setExternalId("BA-123");
        when(em.find(SubmissionRow.class, s.getId())).thenReturn(s);
        service.onEvent(event(w, "lender.decision.received", Map.of("submissionId", s.getId(), "lenderId", s.getLenderId(), "applicationRevision", 1, "lenderApplicationId", s.getExternalId(), "decisionId", UUID.randomUUID(), "decision", "REJECTED", "reasonCode", "POLICY_RULE", "decidedAt", Instant.now().toString()), "lender-integration-service"));
        assertEquals("REJECTED", w.getStatus());
    }

    private SubmissionRow submission(WorkflowRow w, String externalId) {
        w.setSubmissionId(UUID.randomUUID());
        w.setLenderId(UUID.randomUUID().toString());
        SubmissionRow s = new SubmissionRow();
        s.setId(w.getSubmissionId());
        s.setApplicationId(w.getId());
        s.setLenderId(w.getLenderId());
        s.setRevision(w.getRevision());
        s.setExternalId(externalId);
        s.setStatus("CREATED");
        when(em.find(SubmissionRow.class, s.getId())).thenReturn(s);
        return s;
    }

    private Map<String, Object> lenderData(WorkflowRow w, SubmissionRow s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("submissionId", s.getId());
        d.put("lenderId", w.getLenderId());
        d.put("applicationRevision", w.getRevision());
        d.put("lenderApplicationId", "BA-123");
        return d;
    }

    private void lenderEvent(WorkflowRow w, String type, Map<String, Object> data, long sequence) {
        Event e = event(w, type, data, "lender-integration-service");
        service.onEvent(new Event(e.eventId(), e.eventType(), e.schemaVersion(), e.occurredAt(), e.producer(), e.applicationId(), sequence, e.correlationId(), e.causationId(), e.data(), e.actor(), e.originActor()));
    }

    @Test
    void lateReceiptAfterCancellationDoesNotBlockCancellationAcknowledgement() throws Exception {
        WorkflowRow w = workflow("CANCELLED");
        configure(w);
        SubmissionRow s = submission(w, null);
        Map<String, Object> receipt = lenderData(w, s);
        // Integration does not repeat the envelope eventType inside data.
        assertFalse(receipt.containsKey("eventType"));
        lenderEvent(w, "lender.application.submitted", receipt, 1);
        assertEquals("BA-123", s.getExternalId());
        assertEquals("CANCELLED", w.getStatus());
        Map<String, Object> ack = lenderData(w, s);
        ack.put("cancellationAcknowledgementStatus", "ACKNOWLEDGED");
        lenderEvent(w, "lender.cancellation.acknowledged", ack, 2);
        assertEquals("CANCELLED", w.getStatus());
        assertEquals("SUBMITTED", s.getStatus());
        assertEquals("ACKNOWLEDGED", s.getCancellationAcknowledgement());
        assertEquals(2, w.getLastIntegrationVersion());
    }

    @Test
    void lateReceiptDoesNotReopenAnAcknowledgedCancellation() throws Exception {
        WorkflowRow w = workflow("CANCELLED");
        configure(w);
        SubmissionRow s = submission(w, null);
        s.setStatus("SUBMITTED");
        s.setCancellationAcknowledgement("ACKNOWLEDGED");
        lenderEvent(w, "lender.application.submitted", lenderData(w, s), 1);
        assertEquals("BA-123", s.getExternalId());
        assertEquals("SUBMITTED", s.getStatus());
        assertEquals("CANCELLED", w.getStatus());
    }

    @Test
    void lateReceiptCannotReplaceAnExistingExternalId() throws Exception {
        WorkflowRow w = workflow("CANCELLED");
        configure(w);
        SubmissionRow s = submission(w, "BA-ORIGINAL");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> lenderEvent(w, "lender.application.submitted", lenderData(w, s), 1));
        assertEquals("LENDER_EXTERNAL_ID_MISMATCH", error.getReason());
        assertEquals("BA-ORIGINAL", s.getExternalId());
        assertEquals("CANCELLED", w.getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test
    void offerSupersedesOpenQuestionsAndLaterAnswerAckDoesNotBlockSequence() throws Exception {
        WorkflowRow w = workflow("ACTION_REQUIRED");
        configure(w);
        SubmissionRow s = submission(w, "BA-123");
        InformationRow q = new InformationRow();
        q.setId(UUID.randomUUID());
        q.setApplicationId(w.getId());
        q.setSubmissionId(s.getId());
        q.setStatus("OPEN");
        q.setVersion(0);
        when(em.find(InformationRow.class, q.getId())).thenReturn(q);
        TypedQuery<Long> count = mock(TypedQuery.class);
        when(em.createQuery("select count(o) from CfOffer o where o.applicationId=:a", Long.class)).thenReturn(count);
        when(count.setParameter("a", w.getId())).thenReturn(count);
        when(count.getSingleResult()).thenReturn(0L);
        TypedQuery<InformationRow> open = mock(TypedQuery.class);
        when(em.createQuery("select q from CfInformation q where q.applicationId=:a and q.status='OPEN'", InformationRow.class)).thenReturn(open);
        when(open.setParameter("a", w.getId())).thenReturn(open);
        when(open.getResultList()).thenReturn(List.of(q));
        Map<String, Object> d = lenderData(w, s);
        d.put("offerId", UUID.randomUUID());
        d.put("offeredAmount", "5000.00");
        d.put("feesAmount", "0.00");
        d.put("annualInterestRatePercent", "12.0000");
        d.put("currency", "INR");
        d.put("interestRateType", "FIXED");
        d.put("repaymentFrequency", "MONTHLY");
        d.put("tenureMonths", 12);
        d.put("termsReference", "synthetic://terms/1");
        d.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        lenderEvent(w, "loan.offer.created", d, 1);
        assertEquals("APPROVED", w.getStatus());
        assertEquals("CANCELLED", q.getStatus());
        assertEquals("APPROVED", s.getStatus());
        assertEquals(1, q.getVersion());
        Map<String, Object> ack = lenderData(w, s);
        ack.put("informationRequestId", q.getId());
        lenderEvent(w, "lender.information.answered", ack, 2);
        assertEquals(2, w.getLastIntegrationVersion());
        assertEquals("APPROVED", w.getStatus());
        assertEquals("CANCELLED", q.getStatus());
    }

    private Map<String, Object> grant(WorkflowRow w, List<String> scopes, List<String> recipients) {
        return Map.of("consentId", w.getConsentId(), "consentVersion", 2, "status", "GRANTED", "scopes", scopes, "sourceMarketplaceIds", List.of(w.getMarketplaceId().toString()), "recipientLenderIds", recipients, "purpose", "Evaluate this application", "validityPolicy", "APPLICATION_OPEN");
    }

    @SuppressWarnings("unchecked")
    private void cancellationQueries(WorkflowRow w) {
        TypedQuery<OfferRow> offers = mock(TypedQuery.class);
        when(em.createQuery("select o from CfOffer o where o.applicationId=:a and o.status='ACTIVE'", OfferRow.class)).thenReturn(offers);
        when(offers.setParameter("a", w.getId())).thenReturn(offers);
        when(offers.getResultList()).thenReturn(List.of());
        Query questions = mock(Query.class);
        when(em.createQuery("update CfInformation q set q.status='CANCELLED' where q.applicationId=:a and q.status='OPEN'")).thenReturn(questions);
        when(questions.setParameter("a", w.getId())).thenReturn(questions);
    }

    @Test
    void removingSelectedRecipientPausesAnAuthorizedDispatch() throws Exception {
        WorkflowRow w = workflow("SUBMITTING");
        configure(w);
        SubmissionRow s = submission(w, "BA-123");
        w.setDispatchAuthorized(true);
        w.setConsentJson(mapper.writeValueAsString(Map.of("recipientLenderIds", List.of(w.getLenderId()))));
        cancellationQueries(w);
        service.onEvent(event(w, "consent.updated", grant(w, List.of("MARKETPLACE_DATA_READ", "IDENTITY_VERIFICATION", "LENDER_APPLICATION_SHARE"), List.of(UUID.randomUUID().toString())), "consent-verification-service"));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        assertFalse(w.isConsentGranted());
        assertFalse(w.isDispatchAuthorized());
        assertNull(w.getCancellationId());
        verify(p,never()).job(any(),eq("integration"),contains("/cancellation"),any(),anyString(),any(),any());
        JsonNode authorization = service.authorization(s.getId(), mock(Jwt.class));
        assertFalse(authorization.path("authorized").asBoolean());
    }

    @Test
    void removingRequiredScopePausesAnActiveApplicationBeforeDispatch() throws Exception {
        WorkflowRow w = workflow("SUBMITTED");
        configure(w);
        cancellationQueries(w);
        service.onEvent(event(w, "consent.updated", grant(w, List.of("MARKETPLACE_DATA_READ", "IDENTITY_VERIFICATION"), List.of()), "consent-verification-service"));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        assertFalse(w.isConsentGranted());
        assertFalse(w.isDispatchAuthorized());
        verify(p, never()).job(any(), eq("integration"), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void incompleteInitialGrantRemainsGatedWithoutStartingVerification() throws Exception {
        WorkflowRow w = workflow("DRAFT");
        configure(w);
        w.setConsentGranted(false);
        service.onEvent(event(w, "consent.updated", grant(w, List.of("MARKETPLACE_DATA_READ"), List.of()), "consent-verification-service"));
        assertEquals("DRAFT", w.getStatus());
        assertFalse(w.isConsentGranted());
        assertEquals("REQUIRED_CONSENT_INCOMPLETE", w.getDetail());
        verify(p, never()).job(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void removingRecipientDoesNotRewriteAnAlreadyAcceptedOutcome() throws Exception {
        WorkflowRow w = workflow("OFFER_ACCEPTED");
        configure(w);
        submission(w, "BA-123");
        w.setDispatchAuthorized(true);
        w.setConsentJson(mapper.writeValueAsString(Map.of("recipientLenderIds", List.of(w.getLenderId()))));
        service.onEvent(event(w, "consent.updated", grant(w, List.of("MARKETPLACE_DATA_READ", "IDENTITY_VERIFICATION", "LENDER_APPLICATION_SHARE"), List.of()), "consent-verification-service"));
        assertEquals("OFFER_ACCEPTED", w.getStatus());
        assertFalse(w.isConsentGranted());
        assertFalse(w.isDispatchAuthorized());
        verify(p, never()).job(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void answerAcknowledgementCannotMutateAnotherApplicationsQuestion() throws Exception {
        WorkflowRow w = workflow("ACTION_REQUIRED");
        configure(w);
        SubmissionRow s = submission(w, "BA-123");
        InformationRow other = new InformationRow();
        other.setId(UUID.randomUUID());
        other.setApplicationId(UUID.randomUUID());
        other.setSubmissionId(UUID.randomUUID());
        other.setStatus("OPEN");
        other.setAnswersJson("[]");
        when(em.find(InformationRow.class, other.getId())).thenReturn(other);
        Map<String, Object> d = lenderData(w, s);
        d.put("informationRequestId", other.getId());
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> lenderEvent(w, "lender.information.answered", d, 1));
        assertEquals("LENDER_RESULT_CHILD_BINDING_MISMATCH", error.getReason());
        assertEquals("OPEN", other.getStatus());
        assertNull(other.getAcknowledgement());
    }

    @Test
    void withdrawalCannotMutateAnotherApplicationsOffer() throws Exception {
        WorkflowRow w = workflow("APPROVED");
        configure(w);
        SubmissionRow s = submission(w, "BA-123");
        OfferRow other = new OfferRow();
        other.setId(UUID.randomUUID());
        other.setApplicationId(UUID.randomUUID());
        other.setSubmissionId(UUID.randomUUID());
        other.setStatus("ACTIVE");
        when(em.find(OfferRow.class, other.getId())).thenReturn(other);
        Map<String, Object> d = lenderData(w, s);
        d.put("offerId", other.getId());
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> lenderEvent(w, "loan.offer.withdrawn", d, 1));
        assertEquals("LENDER_RESULT_CHILD_BINDING_MISMATCH", error.getReason());
        assertEquals("ACTIVE", other.getStatus());
        assertEquals("APPROVED", w.getStatus());
    }

    @Test
    void acceptanceAcknowledgementCannotMutateAnOfferFromAnotherSubmission() throws Exception {
        WorkflowRow w = workflow("OFFER_ACCEPTED");
        configure(w);
        SubmissionRow s = submission(w, "BA-123");
        OfferRow other = new OfferRow();
        other.setId(UUID.randomUUID());
        other.setApplicationId(w.getId());
        other.setSubmissionId(UUID.randomUUID());
        other.setStatus("ACCEPTED");
        other.setAcceptanceId(UUID.randomUUID());
        other.setAcknowledgement("PENDING");
        when(em.find(OfferRow.class, other.getId())).thenReturn(other);
        Map<String, Object> d = lenderData(w, s);
        d.put("offerId", other.getId());
        d.put("acceptanceId", other.getAcceptanceId());
        d.put("lenderAcknowledgementStatus", "ACKNOWLEDGED");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> lenderEvent(w, "loan.offer.acknowledged", d, 1));
        assertEquals("LENDER_RESULT_CHILD_BINDING_MISMATCH", error.getReason());
        assertEquals("PENDING", other.getAcknowledgement());
    }
}
