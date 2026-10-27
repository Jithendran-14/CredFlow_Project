package com.credflow.service;

import com.credflow.dto.WorkflowReply;
import com.credflow.dto.WorkflowVersionRequest;
import com.credflow.entity.WorkflowRow;
import com.credflow.platform.Platform.*;
import com.credflow.platform.Platform;
import com.credflow.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamContractAlignmentTest {

    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    final Platform p = mock(Platform.class);

    final EntityManager em = mock(EntityManager.class);

    final WorkflowService service = new WorkflowService(new WorkflowRepository(em), p, "integration", "application", "consent");

    final Actor seller = new Actor("HUMAN", "seller-login-subject", null);

    WorkflowRow workflow(String status) throws Exception {
        WorkflowRow w = new WorkflowRow();
        w.setId(UUID.randomUUID());
        w.setMarketplaceId(UUID.randomUUID());
        w.setSellerId("SELLER-1001");
        w.setSellerSubject(seller.subject());
        w.setMarketplaceSellerId("EXT-SELLER-44");
        w.setStatus(status);
        w.setHealth("OK");
        w.setRevision(1);
        w.setWorkflowVersion(1);
        w.setVerificationId(UUID.randomUUID());
        w.setEvaluationId(UUID.randomUUID());
        w.setEvaluationAsOfDate(LocalDate.of(2026, 10, 13));
        w.setAuthorisedRetrievalReference("trusted-source-104");
        w.setConsentId(UUID.randomUUID());
        w.setConsentVersion(1);
        w.setConsentGranted(true);
        w.setAmount(new BigDecimal("500000.00"));
        w.setCurrency("INR");
        w.setPurpose("WORKING_CAPITAL");
        w.setLegalName("Synthetic shop");
        w.setProfileJson("{\"businessName\":\"Synthetic shop\",\"phase0\":{\"preferredTenureMonths\":12,\"declaredMonthlyIncome\":\"500000.00\",\"monthlyExpenses\":\"100000.00\",\"existingMonthlyDebt\":\"10000.00\",\"dateOfBirth\":\"1990-01-01\",\"documentType\":\"MOCK_ID\",\"documentReference\":\"TEST-IDENTITY001\",\"detailsConfirmed\":true,\"kycTermsAccepted\":true}}");
        when(em.find(eq(WorkflowRow.class), eq(w.getId()), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(w);
        when(em.find(WorkflowRow.class, w.getId())).thenReturn(w);
        when(p.call(eq("consent"),any(),anyString(),isNull(),isNull(),anyString())).thenAnswer(a -> mapper.valueToTree(Map.of("applicationId",w.getId().toString(),"applicationRevision",w.getRevision(),"status","ACTIVE","operationalConsentVersion",w.getConsentVersion(),"expiresAt",Instant.now().plusSeconds(86400).toString())));
        when(p.json(any())).thenAnswer(a -> mapper.valueToTree(a.getArgument(0)));
        when(p.write(any())).thenAnswer(a -> mapper.writeValueAsString(a.getArgument(0)));
        when(p.read(anyString(), eq(JsonNode.class))).thenAnswer(a -> mapper.readTree((String) a.getArgument(0)));
        when(p.systemActor()).thenReturn(new Actor("SYSTEM", "orchestration-service", null));
        when(p.actor(any())).thenReturn(seller);
        return w;
    }

    Event event(WorkflowRow w, String type, Map<String, Object> d) {
        String producer = type.startsWith("application.") ? "application-service" : type.equals("eligibility.completed") ? "eligibility-service" : "consent-verification-service";
        return new Event(UUID.randomUUID(), type, "1.0", Instant.now(), producer, w.getId(), 1, "team-contract", null, mapper.valueToTree(d), new Actor("SYSTEM_CLIENT", producer, null), seller);
    }

    Map<String, Object> verification(WorkflowRow w) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("applicationRevision", w.getRevision());
        d.put("verificationId", w.getVerificationId());
        d.put("status", "VERIFIED");
        d.put("authorisedRetrievalReference", "trusted-source-104");
        return d;
    }

    Map<String, Object> result(WorkflowRow w) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("applicationId", w.getId());
        d.put("applicationRevision", w.getRevision());
        d.put("verificationId", w.getVerificationId());
        d.put("evaluationRequestId", w.getEvaluationId());
        d.put("eligibilityResultId", UUID.randomUUID());
        d.put("financialSnapshotId", UUID.randomUUID());
        d.put("calculationVersion", "1.0");
        d.put("evaluatedAt", Instant.now().toString());
        d.put("asOfDate", w.getEvaluationAsOfDate().toString());
        d.put("requestedAmount", "500000.00");
        d.put("currency", "INR");
        d.put("outcome", "EVALUATED");
        d.put("reasonCodes", List.of());
        d.put("averageMonthlyNetSales", "550000.00");
        d.put("averageMonthlyPayout", "530000.00");
        d.put("returnRatePercent", "8.33");
        d.put("lenderResults", List.of(Map.of("lenderId", UUID.randomUUID(), "productId", UUID.randomUUID(), "ruleVersion", "1", "indicativeMaxAmount", "550000.00", "eligible", false, "reasonCodes", List.of("BUSINESS_TOO_YOUNG"), "routingPriority", 1)));
        return d;
    }

    Map<String, Object> application(WorkflowRow w) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sellerId", "SELLER-1001");
        d.put("marketplaceId", w.getMarketplaceId());
        d.put("applicationRevision", 1);
        d.put("requestedAmount", "500000.00");
        d.put("currency", "INR");
        d.put("loanPurpose", "WORKING_CAPITAL");
        d.put("businessProfile", Map.of("businessName", "Synthetic shop","phase0",Map.of("preferredTenureMonths",12,"dateOfBirth","1990-01-01","documentType","MOCK_ID","documentReference","TEST-IDENTITY001","detailsConfirmed",true,"kycTermsAccepted",true)));
        return d;
    }

    @Test
    void memberOneTrustedSellerIdIsAStringAndLegacySubjectIsOptional() throws Exception {
        WorkflowRow w = workflow("DRAFT");
        when(em.find(WorkflowRow.class, w.getId())).thenReturn(null);
        service.onEvent(event(w, "application.submitted", application(w)));
        ArgumentCaptor<WorkflowRow> saved = ArgumentCaptor.forClass(WorkflowRow.class);
        verify(em).persist(saved.capture());
        assertEquals("SELLER-1001", saved.getValue().getSellerId());
        assertNull(saved.getValue().getSellerSubject());
        assertNull(saved.getValue().getMarketplaceSellerId());
        assertEquals("DRAFT", saved.getValue().getStatus());
    }

    @Test
    void missingApplicationDetailsProduceAnExplicitGateWithoutInventingBusinessFields() throws Exception {
        WorkflowRow w = workflow("DRAFT");
        when(em.find(WorkflowRow.class, w.getId())).thenReturn(null);
        Map<String, Object> d = application(w);
        d.remove("businessProfile");
        d.remove("loanPurpose");
        service.onEvent(event(w, "application.submitted", d));
        ArgumentCaptor<WorkflowRow> saved = ArgumentCaptor.forClass(WorkflowRow.class);
        verify(em).persist(saved.capture());
        assertEquals("ACTION_REQUIRED", saved.getValue().getStatus());
        assertEquals("APPLICATION_DETAILS_REQUIRED", saved.getValue().getDetail());
        assertNull(saved.getValue().getPurpose());
        assertNull(saved.getValue().getLegalName());
        assertNull(saved.getValue().getProfileJson());
        verify(p, never()).job(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    Map<String, Object> consent(WorkflowRow w) {
        return Map.of("consentId", w.getConsentId(), "consentVersion", 2, "status", "GRANTED", "scopes", List.of("MARKETPLACE_DATA_READ", "IDENTITY_VERIFICATION", "LENDER_APPLICATION_SHARE"), "sourceMarketplaceIds", List.of(w.getMarketplaceId().toString()), "recipientLenderIds", List.of(), "purpose", "Application review", "validityPolicy", "APPLICATION_OPEN");
    }

    @Test
    void unmappedSellerNeverUsesMarketplaceSellerIdAsAnOwnershipOrFixtureSubstitute() throws Exception {
        WorkflowRow w = workflow("DRAFT");
        w.setSellerId(null);
        service.onEvent(event(w, "consent.updated", consent(w)));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        assertEquals("TRUSTED_SELLER_MAPPING_REQUIRED", w.getDetail());
        verify(p, never()).job(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void consentVerificationTriggerRemainsConfigurableAndDoesNotCallTheProviderMock() throws Exception {
        WorkflowRow w = workflow("DRAFT");
        ReflectionTestUtils.setField(service, "verificationTriggerPath", "/internal/v1/agreed-consent-trigger");
        service.onEvent(event(w, "consent.updated", consent(w)));
        verify(p).job(eq(w.getVerificationId()), eq("consent"), eq("/internal/v1/agreed-consent-trigger"), any(), anyString(), eq(w.getId()), eq(seller));
        assertEquals("SUBMITTED", w.getStatus());
    }

    @Test
    void evaluationUsesMemberTwoPathSixInputsAndOneCapturedUtcDateAcrossDuplicateEvents() throws Exception {
        WorkflowRow w = workflow("SUBMITTED");
        LocalDate before = LocalDate.now(ZoneOffset.UTC);
        Event e = event(w, "verification.completed", verification(w));
        service.onEvent(e);
        UUID evaluationId = w.getEvaluationId();
        LocalDate asOfDate = w.getEvaluationAsOfDate();
        service.onEvent(e);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(p, times(1)).job(eq(evaluationId), eq("eligibility"), eq("/internal/v1/eligibility/evaluations"), payload.capture(), eq(evaluationId.toString()), eq(w.getId()), eq(seller));
        JsonNode body = mapper.valueToTree(payload.getValue());
        assertEquals(6, body.size());
        assertEquals(w.getId().toString(), body.path("applicationId").asText());
        assertEquals(w.getVerificationId().toString(), body.path("verificationId").asText());
        assertEquals("trusted-source-104", body.path("authorisedRetrievalReference").asText());
        assertEquals(asOfDate, LocalDate.parse(body.path("asOfDate").asText()));
        assertFalse(asOfDate.isBefore(before));
        assertFalse(asOfDate.isAfter(LocalDate.now(ZoneOffset.UTC)));
        assertEquals(evaluationId, w.getEvaluationId());
        assertNull(w.getSnapshotId());
    }

    @Test
    void verifiedIdentityAndInitialSharingConsentDoNotInventFinancialRetrievalAuthority() throws Exception {
        WorkflowRow w = workflow("SUBMITTED");
        Map<String, Object> d = verification(w);
        d.remove("authorisedRetrievalReference");
        d.put("providerReference", "mock-verification-provider-only");
        d.put("initialSharingConsent", true);
        service.onEvent(event(w, "verification.completed", d));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        assertEquals("FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED", w.getDetail());
        verify(p, never()).job(any(), eq("eligibility"), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void newlyAvailableFinancialAuthorityCanRetryVerificationWithoutInventingAnApplicationCorrection() throws Exception {
        WorkflowRow w = workflow("ACTION_REQUIRED");
        w.setDetail("FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED");
        UUID previousVerification = w.getVerificationId();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(seller.subject());
        when(p.hash(any())).thenReturn("retry-reference-key");
        WorkflowReply reply = service.command(w.getId(), new WorkflowVersionRequest(1L), "refresh-authority", jwt, true);
        assertEquals(202, reply.status());
        assertEquals("SUBMITTED", w.getStatus());
        assertEquals(1, w.getRevision());
        assertEquals(1, w.getLastRefreshRevision());
        assertNotEquals(previousVerification, w.getVerificationId());
        assertNull(w.getAuthorisedRetrievalReference());
        assertNull(w.getEvaluationAsOfDate());
        assertNull(w.getEvaluationId());
        verify(p).job(eq(w.getVerificationId()), eq("consent"), eq("/api/v1/internal/verifications"), any(), eq(w.getVerificationId().toString()), eq(w.getId()), eq(seller));
        verify(p, never()).job(any(), eq("eligibility"), anyString(), any(), anyString(), any(), any());
        Map<String, Object> withoutAuthority = verification(w);
        withoutAuthority.remove("authorisedRetrievalReference");
        service.onEvent(event(w, "verification.completed", withoutAuthority));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        when(p.hash(any())).thenReturn("second-retry-reference-key");
        assertEquals(202,service.command(w.getId(),new WorkflowVersionRequest(w.getWorkflowVersion()),"retry-again",jwt,true).status());
        assertEquals("SUBMITTED",w.getStatus());
    }

    @Test
    void resultMustBindNestedApplicationDateAmountAndCurrencyBeforeSavingSnapshot() throws Exception {
        for (String field : List.of("applicationId", "asOfDate", "requestedAmount", "currency")) {
            WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
            Map<String, Object> d = result(w);
            d.put(field, switch(field) {
                case "applicationId" ->
                    UUID.randomUUID();
                case "asOfDate" ->
                    "2026-10-12";
                case "requestedAmount" ->
                    "500001.00";
                default ->
                    "USD";
            });
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.onEvent(event(w, "eligibility.completed", d))).getStatusCode().value());
            assertNull(w.getSnapshotId());
            assertNull(w.getEligibilityId());
            assertEquals("ELIGIBILITY_CHECKING", w.getStatus());
        }
    }

    @Test
    void evaluatedSummaryFieldsMustBeTwoDecimalStrings() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Map<String, Object> d = result(w);
        d.put("averageMonthlyNetSales", 550000.00);
        assertEquals("MISSING_averageMonthlyNetSales", assertThrows(ResponseStatusException.class, () -> service.onEvent(event(w, "eligibility.completed", d))).getReason());
        assertNull(w.getSnapshotId());
        assertNull(w.getEligibilityId());
    }

    @Test
    void inconsistentFinancialDataIsActionRequiredAndUnknownSummariesCanBeOmitted() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Map<String, Object> d = result(w);
        d.put("outcome", "ACTION_REQUIRED");
        d.put("reasonCodes", List.of("FINANCIAL_DATA_INCONSISTENT"));
        d.put("lenderResults", List.of());
        for (String field : List.of("averageMonthlyNetSales", "averageMonthlyPayout", "returnRatePercent")) d.remove(field);
        service.onEvent(event(w, "eligibility.completed", d));
        assertEquals("ACTION_REQUIRED", w.getStatus());
        assertEquals("FINANCIAL_DATA_INCONSISTENT", w.getDetail());
        assertEquals(d.get("financialSnapshotId"), w.getSnapshotId());
        assertNull(w.getSubmissionId());
    }

    Job evaluationJob(WorkflowRow w) {
        return new Job("eligibility", "/internal/v1/eligibility/evaluations", mapper.valueToTree(Map.of("evaluationRequestId", w.getEvaluationId(), "applicationId", w.getId(), "applicationRevision", w.getRevision(), "verificationId", w.getVerificationId(), "asOfDate", w.getEvaluationAsOfDate().toString(), "authorisedRetrievalReference", w.getAuthorisedRetrievalReference())), w.getEvaluationId().toString(), seller, "team-contract");
    }

    @Test
    void durableEvaluationRetryCannotChangeItsDateOrTrustedReference() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Job job = evaluationJob(w);
        assertTrue(service.canRunJob(w.getId(), w.getEvaluationId(), job));
        ((com.fasterxml.jackson.databind.node.ObjectNode) job.payload()).put("asOfDate", "2026-10-14");
        assertFalse(service.canRunJob(w.getId(), w.getEvaluationId(), job));
        ((com.fasterxml.jackson.databind.node.ObjectNode) job.payload()).put("asOfDate", "2026-10-13");
        ((com.fasterxml.jackson.databind.node.ObjectNode) job.payload()).put("authorisedRetrievalReference", "unrelated-source");
        assertFalse(service.canRunJob(w.getId(), w.getEvaluationId(), job));
    }

    @Test
    void asynchronousEligibilityOperationIsPersistedAndExposedWithoutLeakingRetrievalReference() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Job job = evaluationJob(w);
        UUID operation = UUID.randomUUID();
        JsonNode receipt = mapper.valueToTree(Map.of("operationId", operation, "status", "PENDING", "statusUrl", "/api/v1/operations/" + operation));
        service.onJobResponse(w.getId(), w.getEvaluationId(), job, receipt);
        service.onJobResponse(w.getId(), w.getEvaluationId(), job, receipt);
        JsonNode view = service.workflow(w.getId(), mock(Jwt.class));
        assertEquals(operation.toString(), view.path("eligibilityOperationId").asText());
        assertEquals("/api/v1/operations/" + operation, view.path("eligibilityStatusUrl").asText());
        assertEquals("2026-10-13", view.path("asOfDate").asText());
        assertFalse(view.has("authorisedRetrievalReference"));
        verify(p, times(1)).emit(eq("application.status.changed"), eq(w.getId()), anyLong(), any(), any(), any(), any());
    }

    @Test
    void staleEligibilityOperationReceiptCannotReplaceCurrentOperation() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Job job = evaluationJob(w);
        service.onJobResponse(w.getId(), UUID.randomUUID(), job, mapper.createObjectNode());
        assertNull(w.getEligibilityOperationId());
        assertNull(w.getEligibilityStatusUrl());
    }

    @Test
    void operationReceiptCannotSupplyAnExternalPollingUrl() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        UUID operation = UUID.randomUUID();
        JsonNode receipt = mapper.valueToTree(Map.of("operationId", operation, "status", "PENDING", "statusUrl", "https://untrusted.invalid/api/v1/operations/" + operation));
        assertEquals("INVALID_ELIGIBILITY_OPERATION_RESPONSE", assertThrows(ResponseStatusException.class, () -> service.onJobResponse(w.getId(), w.getEvaluationId(), evaluationJob(w), receipt)).getReason());
        assertNull(w.getEligibilityOperationId());
    }

    @Test
    void identicalEvaluationInputsCannotAcquireADifferentOperationIdOnRetry() throws Exception {
        WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
        Job job = evaluationJob(w);
        UUID first = UUID.randomUUID();
        service.onJobResponse(w.getId(), w.getEvaluationId(), job, mapper.valueToTree(Map.of("operationId", first, "status", "PENDING", "statusUrl", "/api/v1/operations/" + first)));
        UUID second = UUID.randomUUID();
        assertEquals("ELIGIBILITY_OPERATION_REFERENCE_CONFLICT", assertThrows(ResponseStatusException.class, () -> service.onJobResponse(w.getId(), w.getEvaluationId(), job, mapper.valueToTree(Map.of("operationId", second, "status", "PENDING", "statusUrl", "/api/v1/operations/" + second)))).getReason());
        assertEquals(first, w.getEligibilityOperationId());
    }
}
