package com.credflow.service;

import com.credflow.dto.InformationAnswer;
import com.credflow.dto.InformationAnswersRequest;
import com.credflow.dto.OfferActionRequest;
import com.credflow.dto.WorkflowReply;
import com.credflow.dto.WorkflowVersionRequest;
import com.credflow.entity.IdempotencyRow;
import com.credflow.entity.InformationRow;
import com.credflow.entity.OfferRow;
import com.credflow.entity.SubmissionRow;
import com.credflow.entity.WorkflowRow;
import com.credflow.platform.Platform.*;
import com.credflow.platform.Platform;
import com.credflow.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentAlignmentTest {

	final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	final Platform p = mock(Platform.class);

	final EntityManager em = mock(EntityManager.class);

	final WorkflowService service = new WorkflowService(new WorkflowRepository(em), p, "integration", "application",
			"consent");

	final Actor seller = new Actor("HUMAN", "seller-1", null);

	WorkflowRow workflow(String status) throws Exception {
		WorkflowRow w = new WorkflowRow();
		w.setId(UUID.randomUUID());
		w.setMarketplaceId(UUID.randomUUID());
		w.setSellerSubject(seller.subject());
		w.setSellerId("SELLER-1001");
		w.setEvaluationAsOfDate(java.time.LocalDate.of(2026, 10, 13));
		w.setAuthorisedRetrievalReference("authorised-ref-104");
		w.setStatus(status);
		w.setHealth("OK");
		w.setRevision(1);
		w.setWorkflowVersion(1);
		w.setVerificationId(UUID.randomUUID());
		w.setEvaluationId(UUID.randomUUID());
		w.setConsentId(UUID.randomUUID());
		w.setConsentVersion(1);
		w.setConsentGranted(true);
		w.setAmount(new BigDecimal("500000.00"));
		w.setCurrency("INR");
		w.setPurpose("WORKING_CAPITAL");
		w.setLegalName("Synthetic shop");
		w.setProfileJson(
				"{\"businessName\":\"Synthetic shop\",\"phase0\":{\"preferredTenureMonths\":12,\"declaredMonthlyIncome\":\"500000.00\",\"monthlyExpenses\":\"100000.00\",\"existingMonthlyDebt\":\"10000.00\",\"dateOfBirth\":\"1990-01-01\",\"documentType\":\"MOCK_ID\",\"documentReference\":\"TEST-IDENTITY001\",\"detailsConfirmed\":true,\"kycTermsAccepted\":true}}");
		w.setMarketplaceSellerId("SELLER-1");
		when(em.find(eq(WorkflowRow.class), eq(w.getId()), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(w);
		when(em.find(WorkflowRow.class, w.getId())).thenReturn(w);
		when(p.call(eq("consent"), any(), anyString(), isNull(), isNull(), anyString()))
				.thenAnswer(a -> mapper.valueToTree(Map.of("applicationId", w.getId().toString(), "applicationRevision",
						w.getRevision(), "status", "ACTIVE", "operationalConsentVersion", w.getConsentVersion(),
						"expiresAt", Instant.now().plusSeconds(86400).toString())));
		when(p.json(any())).thenAnswer(a -> mapper.valueToTree(a.getArgument(0)));
		when(p.write(any())).thenAnswer(a -> mapper.writeValueAsString(a.getArgument(0)));
		when(p.read(anyString(), eq(JsonNode.class))).thenAnswer(a -> mapper.readTree((String) a.getArgument(0)));
		when(p.systemActor()).thenReturn(new Actor("SYSTEM", "orchestration-scheduler", null));
		when(p.actor(any())).thenReturn(seller);
		when(p.hash(any())).thenReturn("command-key");
		return w;
	}

	Event event(WorkflowRow w, String type, Map<String, Object> d, long version) {
		String producer = type.startsWith("application.") ? "application-service"
				: type.startsWith("eligibility.") ? "eligibility-service"
						: type.startsWith("verification.") ? "consent-verification-service"
								: "lender-integration-service";
		return new Event(UUID.randomUUID(), type, "1.0", Instant.now(), producer, w.getId(), version, "flow-1", null,
				mapper.valueToTree(d), new Actor("SYSTEM_CLIENT", producer, null), seller);
	}

	Map<String, Object> evaluation(WorkflowRow w, String outcome, boolean eligible) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("applicationId", w.getId());
		d.put("asOfDate", w.getEvaluationAsOfDate().toString());
		d.put("requestedAmount", w.getAmount().toPlainString());
		d.put("currency", w.getCurrency());
		d.put("averageMonthlyNetSales", "550000.00");
		d.put("averageMonthlyPayout", "530000.00");
		d.put("returnRatePercent", "8.33");
		d.put("applicationRevision", w.getRevision());
		d.put("verificationId", w.getVerificationId());
		d.put("evaluationRequestId", w.getEvaluationId());
		d.put("eligibilityResultId", UUID.randomUUID());
		d.put("financialSnapshotId", UUID.randomUUID());
		d.put("outcome", outcome);
		d.put("calculationVersion", "1.0");
		d.put("evaluatedAt", Instant.now().toString());
		d.put("reasonCodes", List.of());
		d.put("lenderResults",
				List.of(Map.of("lenderId", UUID.randomUUID(), "productId", UUID.randomUUID(), "ruleVersion", "1.0",
						"indicativeMaxAmount", "600000.00", "eligible", eligible, "reasonCodes",
						eligible ? List.of() : List.of("BUSINESS_TOO_YOUNG"), "routingPriority", 1)));
		return d;
	}

	Map<String, Object> application(WorkflowRow w, String purpose, Map<String, Object> profile) {
		profile = new LinkedHashMap<>(profile);
		profile.put("phase0",
				Map.of("preferredTenureMonths", 12, "dateOfBirth", "1990-01-01", "documentType", "MOCK_ID",
						"documentReference", "TEST-IDENTITY001", "detailsConfirmed", true, "kycTermsAccepted", true));
		return new LinkedHashMap<>(Map.of("sellerSubject", seller.subject(), "marketplaceSellerId",
				w.getMarketplaceSellerId(), "marketplaceId", w.getMarketplaceId(), "applicationRevision", 1,
				"requestedAmount", "500000.00", "currency", "INR", "loanPurpose", purpose, "businessProfile", profile));
	}

	@Test
	void unsupportedLoanPurposeIsRejectedAtTheApplicationEventBoundary() throws Exception {
		WorkflowRow w = workflow("DRAFT");
		when(em.find(WorkflowRow.class, w.getId())).thenReturn(null);
		Map<String, Object> d = application(w, "PERSONAL_SPENDING", Map.of("businessName", "Synthetic shop"));
		var error = assertThrows(ResponseStatusException.class,
				() -> service.onEvent(event(w, "application.submitted", d, 1)));
		assertEquals(400, error.getStatusCode().value());
		assertEquals("INVALID_loanPurpose", error.getReason());
		verify(em, never()).persist(any(WorkflowRow.class));
	}

	@Test
	void overlongBusinessNamesInEitherSupportedFieldAreRejectedBeforePersistence() throws Exception {
		WorkflowRow w = workflow("DRAFT");
		when(em.find(WorkflowRow.class, w.getId())).thenReturn(null);
		for (String field : List.of("businessName", "registeredBusinessName")) {
			Map<String, Object> d = application(w, "WORKING_CAPITAL", Map.of(field, "x".repeat(256)));
			var error = assertThrows(ResponseStatusException.class,
					() -> service.onEvent(event(w, "application.submitted", d, 1)));
			assertEquals(400, error.getStatusCode().value());
			assertEquals("BUSINESS_NAME_TOO_LONG", error.getReason());
		}
		verify(em, never()).persist(any(WorkflowRow.class));
	}

	@Test
	void allDocumentedLoanPurposesAndTheBusinessNameCharacterBoundaryAreAccepted() throws Exception {
		String businessName = "\uD83C\uDFEA".repeat(255);
		for (String purpose : List.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION")) {
			WorkflowRow w = workflow("DRAFT");
			when(em.find(WorkflowRow.class, w.getId())).thenReturn(null);
			Map<String, Object> d = application(w, purpose, Map.of("businessName", businessName));
			service.onEvent(event(w, "application.submitted", d, 1));
		}
		ArgumentCaptor<WorkflowRow> saved = ArgumentCaptor.forClass(WorkflowRow.class);
		verify(em, times(3)).persist(saved.capture());
		assertEquals(Set.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION"),
				new HashSet<>(saved.getAllValues().stream().map(w -> w.getPurpose()).toList()));
		for (WorkflowRow w : saved.getAllValues()) {
			assertEquals(255, w.getLegalName().codePointCount(0, w.getLegalName().length()));
		}
	}

	SubmissionRow submission(WorkflowRow w) {
		SubmissionRow s = new SubmissionRow();
		s.setId(UUID.randomUUID());
		s.setApplicationId(w.getId());
		s.setLenderId(UUID.randomUUID().toString());
		s.setRevision(w.getRevision());
		s.setExternalId("BA-100");
		s.setStatus("SUBMITTED");
		w.setSubmissionId(s.getId());
		w.setLenderId(s.getLenderId());
		when(em.find(SubmissionRow.class, s.getId())).thenReturn(s);
		return s;
	}

	Map<String, Object> bound(WorkflowRow w, SubmissionRow s) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("submissionId", s.getId());
		d.put("lenderId", s.getLenderId());
		d.put("applicationRevision", w.getRevision());
		d.put("lenderApplicationId", s.getExternalId());
		return d;
	}

	@Test
	void eligibilityOwnsSnapshotAndEvaluatedCanMeanNoEligibleLender() throws Exception {
		WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
		assertNull(w.getSnapshotId());
		Map<String, Object> d = evaluation(w, "EVALUATED", false);
		service.onEvent(event(w, "eligibility.completed", d, 1));
		assertEquals(d.get("financialSnapshotId"), w.getSnapshotId());
		assertEquals("NOT_ELIGIBLE", w.getStatus());
		assertNull(w.getSubmissionId());
		verify(p, never()).job(any(), eq("integration"), anyString(), any(), anyString(), any(), any());
	}

	@Test
	void verificationRequestsEvaluationWithoutInventingASnapshot() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		service.onEvent(event(w, "verification.completed", Map.of("applicationRevision", 1, "verificationId",
				w.getVerificationId(), "status", "VERIFIED", "authorisedRetrievalReference", "trusted-reference-1"),
				1));
		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(p).job(eq(w.getEvaluationId()), eq("eligibility"), eq("/internal/v1/eligibility/evaluations"),
				payload.capture(), anyString(), eq(w.getId()), eq(seller));
		JsonNode d = mapper.valueToTree(payload.getValue());
		assertFalse(d.has("financialSnapshotId"));
		assertNull(w.getSnapshotId());
		assertEquals("ELIGIBILITY_CHECKING", w.getStatus());
	}

	@Test
	void eligibilityIgnoresAStaleRequestWithoutBindingItsSnapshot() throws Exception {
		WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
		Map<String, Object> d = evaluation(w, "EVALUATED", false);
		d.put("evaluationRequestId", UUID.randomUUID());
		service.onEvent(event(w, "eligibility.completed", d, 1));
		assertNull(w.getSnapshotId());
		assertNull(w.getEligibilityId());
		assertEquals("ELIGIBILITY_CHECKING", w.getStatus());
	}

	@Test
	void legacyOverallEligibilityEnumIsRejected() throws Exception {
		WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
		var error = assertThrows(ResponseStatusException.class,
				() -> service.onEvent(event(w, "eligibility.completed", evaluation(w, "ELIGIBLE", true), 1)));
		assertEquals("INVALID_outcome", error.getReason());
		assertNull(w.getSnapshotId());
	}

	@Test
	void missingFinancialDataUsesActionRequiredWithoutLenderResults() throws Exception {
		WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
		Map<String, Object> d = evaluation(w, "ACTION_REQUIRED", false);
		d.put("lenderResults", List.of());
		d.put("reasonCodes", List.of("FINANCIAL_DATA_INCOMPLETE"));
		service.onEvent(event(w, "eligibility.completed", d, 1));
		assertEquals("ACTION_REQUIRED", w.getStatus());
		assertEquals("FINANCIAL_DATA_INCOMPLETE", w.getDetail());
		assertNotNull(w.getSnapshotId());
	}

	@Test
	void aBoundEligibilityResultCannotBeRewritten() throws Exception {
		WorkflowRow w = workflow("ELIGIBILITY_CHECKING");
		Map<String, Object> d = evaluation(w, "EVALUATED", true);
		// Selection waits for named recipient consent.
		w.setConsentGranted(false);
		service.onEvent(event(w, "eligibility.completed", d, 1));
		UUID snapshot = w.getSnapshotId();
		assertEquals("READY_FOR_LENDER", w.getStatus());
		service.onEvent(event(w, "eligibility.completed", d, 1));
		d.put("financialSnapshotId", UUID.randomUUID());
		var error = assertThrows(ResponseStatusException.class,
				() -> service.onEvent(event(w, "eligibility.completed", d, 1)));
		assertEquals("IMMUTABLE_ELIGIBILITY_RESULT_CONFLICT", error.getReason());
		assertEquals(snapshot, w.getSnapshotId());
	}

	@SuppressWarnings("unchecked")
	@Test
	void normalAnswerAcknowledgementResumesDocumentedSubmissionState() throws Exception {
		WorkflowRow w = workflow("ACTION_REQUIRED");
		SubmissionRow s = submission(w);
		s.setStatus("ACTION_REQUIRED");
		InformationRow q = new InformationRow();
		q.setId(UUID.randomUUID());
		q.setApplicationId(w.getId());
		q.setSubmissionId(s.getId());
		q.setStatus("OPEN");
		q.setAnswersJson("[]");
		q.setAcknowledgement("PENDING");
		when(em.find(InformationRow.class, q.getId())).thenReturn(q);
		TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(contains("count(q)"), eq(Long.class))).thenReturn(count);
		when(count.getSingleResult()).thenReturn(0L);
		Map<String, Object> d = bound(w, s);
		d.put("informationRequestId", q.getId());
		service.onEvent(event(w, "lender.information.answered", d, 1));
		assertEquals("UNDER_REVIEW", w.getStatus());
		assertEquals("SUBMITTED", s.getStatus());
		assertEquals("ANSWERED", q.getStatus());
		assertEquals("ACKNOWLEDGED", q.getAcknowledgement());
		service.onEvent(event(w, "lender.information.answered", d, 2));
		assertEquals(1, q.getVersion());
	}

	@Test
	void genericCheckDeliveryFailureAndReplayAreVisibleWithoutBusinessRejection() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		Job job = new Job("consent", "/api/v1/internal/verifications",
				mapper.valueToTree(Map.of("applicationRevision", 1, "verificationId", w.getVerificationId())), "key",
				seller, "flow-1");
		service.onJobState(w.getId(), w.getVerificationId(), job, "PENDING", "DEPENDENCY_UNAVAILABLE");
		assertEquals("RETRY_PENDING", w.getHealth());
		assertEquals("SUBMITTED", w.getStatus());
		JsonNode issue = service.workflow(w.getId(), mock(Jwt.class)).path("processingIssues").get(0);
		assertEquals("JOB", issue.path("kind").asText());
		assertEquals(w.getVerificationId().toString(), issue.path("operationId").asText());
		assertEquals("job:" + w.getVerificationId(), issue.path("issueId").asText());
		service.onJobState(w.getId(), w.getVerificationId(), job, "DEAD", "DEPENDENCY_UNAVAILABLE");
		assertEquals("MANUAL_INTERVENTION_REQUIRED", w.getHealth());
		service.onJobState(w.getId(), w.getVerificationId(), job, "REPLAYED", null);
		assertEquals("RETRY_PENDING", w.getHealth());
		service.onJobState(w.getId(), w.getVerificationId(), job, "DONE", null);
		assertEquals("OK", w.getHealth());
		service.onJobState(w.getId(), w.getVerificationId(), job, "DEAD", "LATE_ERROR");
		assertEquals("OK", w.getHealth());
	}

	@Test
	void recoveringOneTaskDoesNotHideAnotherAndLateFailuresCannotReopenResolvedTasks() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		Map<String, Object> a = bound(w, s);
		a.put("taskId", first);
		a.put("operation", "DECISION");
		a.put("failureCode", "DEPENDENCY_UNAVAILABLE");
		a.put("outcomeUnknown", true);
		Map<String, Object> b = bound(w, s);
		b.put("taskId", second);
		b.put("operation", "CREATE_OFFER");
		b.put("failureCode", "DELIVERY_EXHAUSTED");
		b.put("outcomeUnknown", false);
		service.onEvent(event(w, "lender.processing.failed", a, 1));
		service.onEvent(event(w, "lender.processing.failed", b, 2));
		assertEquals("OUTCOME_UNKNOWN", w.getHealth());
		JsonNode active = service.workflow(w.getId(), mock(Jwt.class)).path("processingIssues");
		assertEquals(2, active.size());
		Set<UUID> operations = new HashSet<>();
		for (JsonNode issue : active) {
			UUID operationId = UUID.fromString(issue.path("operationId").asText());
			operations.add(operationId);
			assertEquals("task:" + operationId, issue.path("issueId").asText());
			assertEquals("TASK", issue.path("kind").asText());
		}
		assertEquals(Set.of(first, second), operations);
		service.onEvent(event(w, "lender.processing.recovered", a, 3));
		assertEquals("MANUAL_INTERVENTION_REQUIRED", w.getHealth());
		JsonNode remaining = service.workflow(w.getId(), mock(Jwt.class)).path("processingIssues");
		assertEquals(1, remaining.size());
		assertEquals(second.toString(), remaining.get(0).path("operationId").asText());
		service.onEvent(event(w, "lender.processing.recovered", b, 4));
		assertEquals("OK", w.getHealth());
		service.onEvent(event(w, "lender.processing.failed", a, 5));
		assertEquals("OK", w.getHealth());
		assertEquals("CANCELLED", w.getStatus());
		assertEquals(2, mapper.readTree(w.getHealthIssuesJson()).size());
	}

	@Test
	void callbackIssueExposesTheOriginalReplayableCallbackOperationId() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		UUID callbackId = UUID.randomUUID();
		Map<String, Object> d = bound(w, s);
		d.put("taskId", callbackId);
		d.put("callbackEventId", callbackId);
		d.put("operation", "CALLBACK");
		d.put("failureCode", "CALLBACK_PREREQUISITE_MISSING");
		d.put("outcomeUnknown", false);
		service.onEvent(event(w, "lender.processing.failed", d, 1));
		JsonNode issue = service.workflow(w.getId(), mock(Jwt.class)).path("processingIssues").get(0);
		assertEquals("CALLBACK", issue.path("kind").asText());
		assertEquals(callbackId.toString(), issue.path("operationId").asText());
		assertEquals("task:" + callbackId, issue.path("issueId").asText());
		assertTrue(mapper.readTree(w.getHealthIssuesJson()).has("task:" + callbackId));
	}

	@Test
	void confirmedCancellationCannotBeDowngradedByALaterFailureAcknowledgement() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		w.setCancellationId(UUID.randomUUID());
		Map<String, Object> d = bound(w, s);
		d.put("cancellationId", w.getCancellationId());
		d.put("cancellationAcknowledgementStatus", "ACKNOWLEDGED");
		service.onEvent(event(w, "lender.cancellation.acknowledged", d, 1));
		assertEquals("ACKNOWLEDGED", s.getCancellationAcknowledgement());
		d.put("taskId", UUID.randomUUID());
		d.put("cancellationAcknowledgementStatus", "FAILED");
		service.onEvent(event(w, "lender.cancellation.acknowledged", d, 2));
		assertEquals("ACKNOWLEDGED", s.getCancellationAcknowledgement());
		assertEquals("OK", w.getHealth());
		assertEquals("CANCELLED", w.getStatus());
		assertEquals(2, w.getLastIntegrationVersion());
	}

	@Test
	void confirmedReferenceCancellationConsumesLateFailureWithoutWaitingForAnExternalReceipt() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		s.setExternalId(null);
		Map<String, Object> d = bound(w, s);
		d.remove("lenderApplicationId");
		d.put("cancellationByReference", true);
		d.put("cancellationAcknowledgementStatus", "ACKNOWLEDGED");
		service.onEvent(event(w, "lender.cancellation.acknowledged", d, 1));
		d.put("taskId", UUID.randomUUID());
		d.put("cancellationAcknowledgementStatus", "FAILED");
		service.onEvent(event(w, "lender.cancellation.acknowledged", d, 2));
		assertNull(s.getExternalId());
		assertEquals("ACKNOWLEDGED", s.getCancellationAcknowledgement());
		assertEquals("OK", w.getHealth());
		assertEquals("CANCELLED", w.getStatus());
		assertEquals(2, w.getLastIntegrationVersion());
	}

	@SuppressWarnings("unchecked")
	@Test
	void answerAcknowledgementClearsOnlyItsOwnDeliveryIssue() throws Exception {
		WorkflowRow w = workflow("ACTION_REQUIRED");
		SubmissionRow s = submission(w);
		InformationRow q = new InformationRow();
		q.setId(UUID.randomUUID());
		q.setApplicationId(w.getId());
		q.setSubmissionId(s.getId());
		q.setStatus("OPEN");
		q.setAnswersJson("[]");
		q.setAcknowledgement("PENDING");
		when(em.find(InformationRow.class, q.getId())).thenReturn(q);
		TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(contains("count(q)"), eq(Long.class))).thenReturn(count);
		when(count.getSingleResult()).thenReturn(0L);
		Map<String, Object> answerFailure = bound(w, s);
		answerFailure.put("taskId", UUID.randomUUID());
		answerFailure.put("operation", "ANSWER_INFORMATION");
		answerFailure.put("informationRequestId", q.getId());
		answerFailure.put("failureCode", "DEPENDENCY_TIMEOUT");
		answerFailure.put("outcomeUnknown", true);
		Map<String, Object> otherFailure = bound(w, s);
		otherFailure.put("taskId", UUID.randomUUID());
		otherFailure.put("operation", "CREATE_OFFER");
		otherFailure.put("failureCode", "DELIVERY_EXHAUSTED");
		otherFailure.put("outcomeUnknown", false);
		service.onEvent(event(w, "lender.processing.failed", answerFailure, 1));
		service.onEvent(event(w, "lender.processing.failed", otherFailure, 2));
		assertEquals("OUTCOME_UNKNOWN", w.getHealth());
		Map<String, Object> ack = bound(w, s);
		ack.put("informationRequestId", q.getId());
		service.onEvent(event(w, "lender.information.answered", ack, 3));
		assertEquals("UNDER_REVIEW", w.getStatus());
		assertEquals("MANUAL_INTERVENTION_REQUIRED", w.getHealth());
		service.onEvent(event(w, "lender.processing.failed", answerFailure, 4));
		assertEquals("MANUAL_INTERVENTION_REQUIRED", w.getHealth());
	}

	@Test
	void aLateOldRevisionDeliveryCannotChangeCurrentHealth() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		Job job = new Job("consent", "/api/v1/internal/verifications",
				mapper.valueToTree(Map.of("applicationRevision", 0, "verificationId", w.getVerificationId())), "key",
				seller, "flow-1");
		service.onJobState(w.getId(), w.getVerificationId(), job, "DEAD", "DEPENDENCY_UNAVAILABLE");
		assertEquals("OK", w.getHealth());
		assertNull(w.getHealthIssuesJson());
		assertFalse(service.canRunJob(w.getId(), w.getVerificationId(), job));
	}

	@Test
	void terminalLateReceiptPublishesHealthRecoveryWithoutReopeningTheApplication() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		s.setExternalId(null);
		Map<String, Object> failure = bound(w, s);
		failure.put("taskId", UUID.randomUUID());
		failure.put("operation", "SUBMIT");
		failure.put("outcomeUnknown", true);
		failure.put("failureCode", "LENDER_TIMEOUT");
		service.onEvent(event(w, "lender.processing.failed", failure, 1));
		assertEquals("OUTCOME_UNKNOWN", w.getHealth());
		clearInvocations(p);
		Map<String, Object> receipt = bound(w, s);
		receipt.put("lenderApplicationId", "BA-100");
		service.onEvent(event(w, "lender.application.submitted", receipt, 2));
		assertEquals("CANCELLED", w.getStatus());
		assertEquals("OK", w.getHealth());
		verify(p).emit(eq("application.status.changed"), eq(w.getId()), anyLong(), any(), any(), any(), any());
	}

	@Test
	void effectiveCancellationVetoesProtectedJobsButAllowsItsCancellationDelivery() throws Exception {
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		w.setConsentGranted(false);
		Job check = new Job("consent", "/api/v1/internal/verifications",
				mapper.valueToTree(Map.of("applicationRevision", 1, "verificationId", w.getVerificationId())), "key",
				seller, "flow-1");
		assertFalse(service.canRunJob(w.getId(), w.getVerificationId(), check));
		w.setCancellationId(UUID.randomUUID());
		Job cancel = new Job("integration", "/api/v1/internal/submissions/" + s.getId() + "/cancellation",
				mapper.valueToTree(Map.of("applicationRevision", 1, "submissionId", s.getId())), "cancel", seller,
				"flow-1");
		assertTrue(service.canRunJob(w.getId(), w.getCancellationId(), cancel));
	}

	@Test
	void everyResourceReadPassesMarketplaceIdentityToOwnershipGuard() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		Jwt jwt = mock(Jwt.class);
		service.workflow(w.getId(), jwt);
		verify(p).owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), w.getMarketplaceId(), true);
	}

	@SuppressWarnings("unchecked")
	@Test
	void operationsSubmissionListFiltersBothItemsAndTotalByMarketplace() throws Exception {
		workflow("SUBMITTED");
		Jwt jwt = mock(Jwt.class);
		UUID marketplace = UUID.randomUUID();
		when(p.hasRole(jwt, "MARKETPLACE_OPERATIONS")).thenReturn(true);
		when(p.marketplaceId(jwt)).thenReturn(marketplace.toString());
		TypedQuery<SubmissionRow> items = mock(TypedQuery.class, RETURNS_SELF);
		TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(anyString(), eq(SubmissionRow.class))).thenReturn(items);
		when(em.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(items.getResultList()).thenReturn(List.of());
		when(count.getSingleResult()).thenReturn(0L);
		service.submissions(0, 20, null, jwt);
		verify(items).setParameter("m", marketplace);
		verify(count).setParameter("m", marketplace);
		verify(em).createQuery(contains("w.marketplaceId=:m"), eq(SubmissionRow.class));
		verify(em).createQuery(contains("w.marketplaceId=:m"), eq(Long.class));
	}

	@Test
	void operationsWithoutMarketplaceIdentityCannotListOtherOrganisations() throws Exception {
		workflow("SUBMITTED");
		Jwt jwt = mock(Jwt.class);
		when(p.hasRole(jwt, "MARKETPLACE_OPERATIONS")).thenReturn(true);
		var error = assertThrows(ResponseStatusException.class, () -> service.submissions(0, 20, null, jwt));
		assertEquals(403, error.getStatusCode().value());
		verify(em, never()).createQuery(anyString(), eq(SubmissionRow.class));
	}

	@Test
	void validAcceptanceIsLocallyFinalWithSeparatePendingLenderAcknowledgement() throws Exception {
		WorkflowRow w = workflow("APPROVED");
		SubmissionRow s = submission(w);
		w.setConsentJson(mapper.writeValueAsString(Map.of("recipientLenderIds", List.of(w.getLenderId()))));
		OfferRow o = new OfferRow();
		o.setId(UUID.randomUUID());
		o.setApplicationId(w.getId());
		o.setSubmissionId(s.getId());
		o.setStatus("ACTIVE");
		o.setAcknowledgement("PENDING");
		o.setVersion(0);
		o.setExpiresAt(Instant.now().plusSeconds(3600));
		o.setAmount(new BigDecimal("500000.00"));
		o.setFees(new BigDecimal("0.00"));
		o.setRate(new BigDecimal("12.00"));
		o.setCurrency("INR");
		o.setInterestType("FIXED");
		o.setFrequency("MONTHLY");
		o.setTenure(12);
		o.setTermsReference("synthetic://terms/1");
		when(em.find(OfferRow.class, o.getId())).thenReturn(o);
		when(em.find(OfferRow.class, o.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(o);
		Jwt jwt = mock(Jwt.class);
		when(jwt.getSubject()).thenReturn(seller.subject());
		when(p.hash(any())).thenAnswer(a -> Integer.toHexString(Objects.hashCode(a.getArgument(0))));
		WorkflowReply reply = service.offerAction(o.getId(), new OfferActionRequest(0L), "accept-1", jwt, true);
		assertEquals(200, reply.status());
		assertEquals("OFFER_ACCEPTED", w.getStatus());
		assertEquals("ACCEPTED", o.getStatus());
		assertEquals("PENDING", o.getAcknowledgement());
		assertNotNull(o.getAcceptanceId());
		assertNotNull(o.getAcceptedAt());
		verify(p).emit(eq("loan.offer.accepted"), eq(w.getId()), anyLong(), any(), eq(seller), eq(seller), isNull());
		ArgumentCaptor<IdempotencyRow> saved = ArgumentCaptor.forClass(IdempotencyRow.class);
		verify(em).persist(saved.capture());
		when(em.find(IdempotencyRow.class, saved.getValue().getId())).thenReturn(saved.getValue());
		WorkflowReply replay = service.offerAction(o.getId(), new OfferActionRequest(0L), "accept-1", jwt, true);
		assertEquals(reply.status(), replay.status());
		assertEquals(mapper.writeValueAsString(reply.body()), mapper.writeValueAsString(replay.body()));
		assertEquals(o.getAcceptedAt(), Instant.parse(reply.body().path("acceptedAt").asText()));
		assertEquals(o.getExpiresAt(), Instant.parse(reply.body().path("expiresAt").asText()));
		var conflict = assertThrows(ResponseStatusException.class,
				() -> service.offerAction(o.getId(), new OfferActionRequest(1L), "accept-1", jwt, true));
		assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.getReason());
		WorkflowReply newKey = service.offerAction(o.getId(), new OfferActionRequest(1L), "accept-2", jwt, true);
		assertEquals(409, newKey.status());
		assertEquals(1, o.getVersion());
		verify(p, times(1)).emit(eq("loan.offer.accepted"), eq(w.getId()), anyLong(), any(), eq(seller), eq(seller),
				isNull());
	}

	@Test
	void syntheticDocumentReferencesAreOptionalAsSpecified() {
		InformationAnswersRequest request = new InformationAnswersRequest(0L,
				List.of(new InformationAnswer("q1", "Synthetic answer")), null);
		assertEquals(List.of(), request.syntheticDocumentReferences());
	}

	@SuppressWarnings("unchecked")
	@Test
	void sellerOfferAndQuestionListsExposeTheDocumentedPageEnvelope() throws Exception {
		WorkflowRow w = workflow("UNDER_REVIEW");
		Jwt jwt = mock(Jwt.class);
		TypedQuery<OfferRow> offers = mock(TypedQuery.class, RETURNS_SELF);
		TypedQuery<InformationRow> questions = mock(TypedQuery.class, RETURNS_SELF);
		TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(anyString(), eq(OfferRow.class))).thenReturn(offers);
		when(em.createQuery(anyString(), eq(InformationRow.class))).thenReturn(questions);
		when(em.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(offers.getResultList()).thenReturn(List.of());
		when(questions.getResultList()).thenReturn(List.of());
		when(count.getSingleResult()).thenReturn(0L);
		JsonNode offerPage = service.offers(w.getId(), 0, 20, jwt);
		JsonNode questionPage = service.questions(w.getId(), 0, 20, jwt);
		for (JsonNode page : List.of(offerPage, questionPage)) {
			assertTrue(page.path("items").isArray());
			assertEquals(0, page.path("page").asInt());
			assertEquals(20, page.path("size").asInt());
			assertEquals(0, page.path("totalElements").asLong());
		}
		verify(offers).setFirstResult(0);
		verify(offers).setMaxResults(20);
		verify(questions).setFirstResult(0);
		verify(questions).setMaxResults(20);
	}

	@Test
	void invalidOrOverflowingPaginationReturnsAValidationError() throws Exception {
		WorkflowRow w = workflow("UNDER_REVIEW");
		Jwt jwt = mock(Jwt.class);
		for (int[] page : List.of(new int[] { -1, 20 }, new int[] { 0, 101 }, new int[] { Integer.MAX_VALUE, 100 })) {
			assertEquals(400,
					assertThrows(ResponseStatusException.class, () -> service.offers(w.getId(), page[0], page[1], jwt))
							.getStatusCode().value());
			assertEquals(400, assertThrows(ResponseStatusException.class,
					() -> service.questions(w.getId(), page[0], page[1], jwt)).getStatusCode().value());
		}
	}

	@Test
	void marketplaceOperationsCannotReadQuestionAnswersOffersOrFullSubmissionDetails() throws Exception {
		WorkflowRow w = workflow("UNDER_REVIEW");
		SubmissionRow s = submission(w);
		Jwt jwt = mock(Jwt.class);
		doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"))
				.when(p).owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), null, true);
		assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.offers(w.getId(), 0, 20, jwt))
				.getStatusCode().value());
		assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.questions(w.getId(), 0, 20, jwt))
				.getStatusCode().value());
		assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.submission(s.getId(), jwt))
				.getStatusCode().value());
		// A distinct marketplace-aware guard remains available for status-only workflow
		// reads.
		assertEquals(w.getStatus(), service.workflow(w.getId(), jwt).path("status").asText());
		verify(p).owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), w.getMarketplaceId(), true);
	}

	@Test
	void answerResponseExposesTheStableDurableJobOperationReference() throws Exception {
		WorkflowRow w = workflow("ACTION_REQUIRED");
		SubmissionRow s = submission(w);
		w.setConsentJson(mapper.writeValueAsString(Map.of("recipientLenderIds", List.of(w.getLenderId()))));
		InformationRow q = new InformationRow();
		q.setId(UUID.randomUUID());
		q.setApplicationId(w.getId());
		q.setSubmissionId(s.getId());
		q.setStatus("OPEN");
		q.setVersion(0);
		q.setQuestionsJson("[{\"questionId\":\"q1\",\"prompt\":\"Synthetic question\"}]");
		when(em.find(InformationRow.class, q.getId())).thenReturn(q);
		when(em.find(InformationRow.class, q.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(q);
		Jwt jwt = mock(Jwt.class);
		when(jwt.getSubject()).thenReturn(seller.subject());
		WorkflowReply reply = service.answer(q.getId(),
				new InformationAnswersRequest(0L, List.of(new InformationAnswer("q1", "Synthetic answer")), null),
				"answer-1", jwt);
		ArgumentCaptor<UUID> operation = ArgumentCaptor.forClass(UUID.class);
		verify(p).job(operation.capture(), eq("integration"), contains("/information-responses"), any(), anyString(),
				eq(w.getId()), eq(seller));
		assertEquals(202, reply.status());
		assertEquals(operation.getValue().toString(), reply.body().path("operationId").asText());
		assertNotNull(q.getAnsweredAt());
		assertEquals("PENDING", q.getAcknowledgement());
	}

	@Test
	void refreshResponseExposesItsExpectedVerificationAndEvaluationReferences() throws Exception {
		WorkflowRow w = workflow("ACTION_REQUIRED");
		w.setDetail("FINANCIAL_DATA_INCOMPLETE");
		Jwt jwt = mock(Jwt.class);
		when(jwt.getSubject()).thenReturn(seller.subject());
		WorkflowReply reply = service.command(w.getId(), new WorkflowVersionRequest(1L), "refresh-1", jwt, true);
		assertEquals(202, reply.status());
		assertEquals(w.getVerificationId().toString(), reply.body().path("operationId").asText());
		assertEquals(w.getVerificationId().toString(), reply.body().path("workflow").path("verificationId").asText());
		assertEquals("SUBMITTED", reply.body().path("workflow").path("status").asText());
	}

	@SuppressWarnings("unchecked")
	@Test
	void localCancellationStillReturnsAnIdempotentOperationReference() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		Jwt jwt = mock(Jwt.class);
		when(jwt.getSubject()).thenReturn(seller.subject());
		TypedQuery<OfferRow> offers = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(anyString(), eq(OfferRow.class))).thenReturn(offers);
		when(offers.getResultList()).thenReturn(List.of());
		Query questions = mock(Query.class, RETURNS_SELF);
		when(em.createQuery(startsWith("update CfInformation"))).thenReturn(questions);
		WorkflowReply reply = service.command(w.getId(), new WorkflowVersionRequest(1L), "cancel-1", jwt, false);
		assertEquals(202, reply.status());
		assertNotNull(UUID.fromString(reply.body().path("operationId").asText()));
		assertEquals("CANCELLED", reply.body().path("workflow").path("status").asText());
		ArgumentCaptor<IdempotencyRow> saved = ArgumentCaptor.forClass(IdempotencyRow.class);
		verify(em).persist(saved.capture());
		when(em.find(IdempotencyRow.class, saved.getValue().getId())).thenReturn(saved.getValue());
		WorkflowReply replay = service.command(w.getId(), new WorkflowVersionRequest(1L), "cancel-1", jwt, false);
		assertEquals(reply.status(), replay.status());
		assertEquals(mapper.writeValueAsString(reply.body()), mapper.writeValueAsString(replay.body()));
	}

	@Test
	void observerStatusEventsRetainTheUpstreamJobCausation() throws Exception {
		WorkflowRow w = workflow("SUBMITTED");
		JsonNode payload = mapper.valueToTree(Map.of("applicationRevision", 1, "verificationId", w.getVerificationId(),
				"causationId", UUID.randomUUID()));
		Job job = new Job("consent", "/api/v1/internal/verifications", payload, "key", seller, "flow-1");
		Event upstream = event(w, "verification.completed", Map.of(), 1);
		when(p.cause(payload)).thenReturn(upstream);
		service.onJobState(w.getId(), w.getVerificationId(), job, "PENDING", "DEPENDENCY_TIMEOUT");
		verify(p).emit(eq("application.status.changed"), eq(w.getId()), anyLong(), any(), any(), eq(seller),
				eq(upstream));
	}

	@SuppressWarnings("unchecked")
	@Test
	void unavailableOptionalQuestionFieldsAreOmittedWithTheConfiguredRealMapper() throws Exception {
		mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
		WorkflowRow w = workflow("ACTION_REQUIRED");
		Jwt jwt = mock(Jwt.class);
		InformationRow q = new InformationRow();
		q.setId(UUID.randomUUID());
		q.setApplicationId(w.getId());
		q.setSubmissionId(UUID.randomUUID());
		q.setStatus("OPEN");
		q.setCreatedAt(Instant.now());
		q.setQuestionsJson("[{\"questionId\":\"q1\",\"prompt\":\"Synthetic question\"}]");
		TypedQuery<InformationRow> questions = mock(TypedQuery.class, RETURNS_SELF);
		TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
		when(em.createQuery(anyString(), eq(InformationRow.class))).thenReturn(questions);
		when(em.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(questions.getResultList()).thenReturn(List.of(q));
		when(count.getSingleResult()).thenReturn(1L);
		JsonNode item = service.questions(w.getId(), 0, 20, jwt).path("items").get(0);
		assertFalse(item.has("answers"));
		assertFalse(item.has("syntheticDocumentReferences"));
		assertFalse(item.has("answeredAt"));
		assertFalse(item.has("lenderAcknowledgementStatus"));
		assertTrue(item.has("createdAt"));
		assertEquals("OPEN", item.path("status").asText());
	}

	@Test
	void unavailableIssueAndWorkflowIdsAreOmittedWithTheConfiguredRealMapper() throws Exception {
		mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
		WorkflowRow w = workflow("CANCELLED");
		SubmissionRow s = submission(w);
		Map<String, Object> d = bound(w, s);
		d.put("taskId", UUID.randomUUID());
		d.put("operation", "DECISION");
		d.put("failureCode", "DELIVERY_EXHAUSTED");
		d.put("outcomeUnknown", false);
		d.put("offerId", null);
		service.onEvent(event(w, "lender.processing.failed", d, 1));
		JsonNode view = service.workflow(w.getId(), mock(Jwt.class));
		JsonNode issue = view.path("processingIssues").get(0);
		for (String field : List.of("informationRequestId", "offerId", "acceptanceId", "cancellationId")) {
			assertFalse(issue.has(field));
		}
		assertFalse(view.has("financialSnapshotId"));
		assertFalse(view.has("eligibilityResultId"));
		assertFalse(view.has("cancellationId"));
		assertTrue(view.has("verificationId"));
	}

	@Test
	void financialPermissionRetryDoesNotConsumeTheDataRefreshLimit() throws Exception {
		WorkflowRow w = workflow("ACTION_REQUIRED");
		w.setDetail("FINANCIAL_CONSENT_REQUIRED");
		w.setLastRefreshRevision(1);
		Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("seller-1").build();
		WorkflowReply reply = service.command(w.getId(), new WorkflowVersionRequest(1L), "permission-retry", jwt, true);
		assertEquals(202, reply.status());
		assertEquals("SUBMITTED", w.getStatus());
	}
}
