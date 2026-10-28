package com.credflow.service;

import com.credflow.dto.InformationAnswer;
import com.credflow.dto.InformationAnswersRequest;
import com.credflow.dto.OfferActionRequest;
import com.credflow.dto.RevisionGuardRequest;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class WorkflowService implements Platform.Handler, Platform.JobObserver {

	private final WorkflowRepository repository;

	private final Platform p;

	private final String integrationClient;

	private final String applicationClient;

	private final String consentClient;
	@Value("${credflow.urls.consent:http://localhost:8082}")
	private String consentBase = "http://localhost:8082";

	// Consent & Verification's orchestration trigger is not agreed in the team
	// contracts yet.
	@Value("${credflow.paths.consent-verification-trigger:/api/v1/internal/verifications}")
	private String verificationTriggerPath = "/api/v1/internal/verifications";

	private static final Set<String> TERMINAL = Set.of("CHECK_FAILED", "NOT_ELIGIBLE", "OFFER_ACCEPTED",
			"OFFER_DECLINED", "OFFER_EXPIRED", "REJECTED", "CANCELLED");

	private static final Map<String, Set<String>> NEXT = Map.ofEntries(
			Map.entry("DRAFT", Set.of("SUBMITTED", "ACTION_REQUIRED")),
			Map.entry("SUBMITTED", Set.of("ACTION_REQUIRED", "ELIGIBILITY_CHECKING")),
			Map.entry("ACTION_REQUIRED",
					Set.of("SUBMITTED", "SUBMITTING", "UNDER_REVIEW", "MANUAL_REVIEW", "REJECTED", "APPROVED",
							"OFFER_EXPIRED")),
			Map.entry("ELIGIBILITY_CHECKING", Set.of("ACTION_REQUIRED", "NOT_ELIGIBLE", "READY_FOR_LENDER")),
			Map.entry("READY_FOR_LENDER", Set.of("SUBMITTING", "ACTION_REQUIRED")),
			Map.entry("SUBMITTING", Set.of("UNDER_REVIEW", "ACTION_REQUIRED")),
			Map.entry("UNDER_REVIEW", Set.of("ACTION_REQUIRED", "MANUAL_REVIEW", "REJECTED", "APPROVED")),
			Map.entry("MANUAL_REVIEW", Set.of("UNDER_REVIEW", "ACTION_REQUIRED", "REJECTED", "APPROVED")),
			Map.entry("APPROVED", Set.of("OFFER_ACCEPTED", "OFFER_DECLINED", "OFFER_EXPIRED", "ACTION_REQUIRED",
					"MANUAL_REVIEW", "REJECTED")));

	public WorkflowService(WorkflowRepository repository, Platform p,
			@Value("${credflow.clients.integration:credflow-integration}") String integrationClient,
			@Value("${credflow.clients.application:credflow-application}") String applicationClient,
			@Value("${credflow.clients.consent:credflow-consent}") String consentClient) {
		this.repository = repository;
		this.p = p;
		this.integrationClient = integrationClient;
		this.applicationClient = applicationClient;
		this.consentClient = consentClient;
	}

	@Override
	public void onEvent(Event e) {
		JsonNode d = e.data();
		String type = e.eventType();
		String expected = type.startsWith("application.") ? "application-service"
				: type.startsWith("consent.") || type.startsWith("financial.consent.")
						|| type.startsWith("verification.") ? "consent-verification-service"
								: type.equals("eligibility.completed") ? "eligibility-service"
										: "lender-integration-service";
		if (!expected.equals(e.producer())) {
			throw bad("UNEXPECTED_EVENT_PRODUCER");
		}
		if (type.equals("application.submitted")) {
			create(e);
			return;
		}
		WorkflowRow w = repository.find(WorkflowRow.class, e.applicationId(), LockModeType.PESSIMISTIC_WRITE);
		if (w == null) {
			throw new Platform.Pending("APPLICATION_NOT_RECEIVED");
		}
		if (type.startsWith("financial.consent.")) {
			if (!TERMINAL.contains(w.getStatus()) && !w.isRevisionPending()
					&& d.path("applicationRevision").asInt() == w.getRevision())
				applyFinancialPermission(w, currentFinancialPermission(w), e);
			return;
		}
		if (type.equals("consent.updated")) {
			consent(w, d, origin(e), e);
			return;
		}
		if (type.equals("application.revised")) {
			revised(w, d, origin(e), e);
			return;
		}
		if (expected.equals("lender-integration-service")) {
			if (e.aggregateVersion() <= w.getLastIntegrationVersion()) {
				return;
			}
			if (e.aggregateVersion() != w.getLastIntegrationVersion() + 1) {
				throw new Platform.Pending("EARLIER_LENDER_EVENT_PENDING");
			}
			w.setLastIntegrationVersion(e.aggregateVersion());
		}
		if (TERMINAL.contains(w.getStatus())) {
			if (type.equals("lender.application.submitted")) {
				String previousHealth = w.getHealth();
				receipt(w, d);
				if (!Objects.equals(previousHealth, w.getHealth())) {
					changed(w, e.actor(), origin(e), e);
				}
			}
			if (type.equals("loan.offer.acknowledged")) {
				offerAck(w, d, e);
			}
			if (type.equals("lender.cancellation.acknowledged")) {
				cancellationAck(w, d, e);
			}
			if (type.equals("lender.processing.failed")) {
				processingFailure(w, d, e);
			}
			if (type.equals("lender.processing.recovered")) {
				processingRecovered(w, d, e);
			}
			return;
		}
		if (type.startsWith("verification.") || type.equals("eligibility.completed")) {
			if (d.path("applicationRevision").asInt() != w.getRevision() || w.isRevisionPending()) {
				return;
			}
		}
		switch (type) {
		case "verification.completed" -> verification(w, d, origin(e), e);
		case "eligibility.completed" -> eligibility(w, d, origin(e), e);
		case "lender.application.submitted" -> {
			String previousHealth = w.getHealth();
			receipt(w, d);
			if (w.getStatus().equals("SUBMITTING")) {
				move(w, "UNDER_REVIEW", e.actor(), origin(e), e);
			} else if (!Objects.equals(previousHealth, w.getHealth())) {
				changed(w, e.actor(), origin(e), e);
			}
		}
		case "lender.information.requested" -> informationRequested(w, d, e);
		case "lender.information.answered" -> informationAcknowledged(w, d, e);
		case "lender.decision.received" -> decision(w, d, e);
		case "loan.offer.created" -> offerCreated(w, d, e);
		case "loan.offer.withdrawn" -> offerWithdrawn(w, d, e);
		case "loan.offer.acknowledged" -> offerAck(w, d, e);
		case "lender.cancellation.acknowledged" -> cancellationAck(w, d, e);
		case "lender.processing.failed" -> processingFailure(w, d, e);
		case "lender.processing.recovered" -> processingRecovered(w, d, e);
		default -> throw bad("UNSUPPORTED_EVENT");
		}
		if (w.getFinancialPermissionJson() != null && !TERMINAL.contains(w.getStatus()))
			applyFinancialPermission(w, currentFinancialPermission(w), e);
	}

	private void create(Event e) {
		if (repository.find(WorkflowRow.class, e.applicationId()) != null) {
			return;
		}
		JsonNode d = e.data();
		WorkflowRow w = new WorkflowRow();
		w.setId(e.applicationId());
		w.setSellerSubject(optionalText(d, "sellerSubject", 255));
		w.setMarketplaceSellerId(optionalText(d, "marketplaceSellerId", 120));
		w.setSellerId(optionalText(d, "sellerId", 120));
		w.setMarketplaceId(uuid(d, "marketplaceId"));
		w.setRevision(positive(d, "applicationRevision"));
		inputs(w, d);
		w.setStatus(applicationDetailsReady(w) ? "DRAFT" : "ACTION_REQUIRED");
		if (!applicationDetailsReady(w))
			w.setDetail("APPLICATION_DETAILS_REQUIRED");
		w.setHealth("OK");
		w.setWorkflowVersion(0);
		w.setOriginJson(p.write(origin(e)));
		repository.save(w);
		changed(w, e.actor(), origin(e), e, null);
	}

	private void inputs(WorkflowRow w, JsonNode d) {
		w.setAmount(money(d, "requestedAmount"));
		if (w.getAmount().signum() <= 0) {
			throw bad("INVALID_REQUESTED_AMOUNT");
		}
		w.setCurrency(oneOf(d, "currency", Set.of("INR")));
		w.setPurpose(d.hasNonNull("loanPurpose")
				? oneOf(d, "loanPurpose", Set.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION"))
				: null);
		JsonNode profile = d.path("businessProfile");
		if (!profile.isMissingNode() && !profile.isNull() && !profile.isObject()) {
			throw bad("INVALID_businessProfile");
		}
		w.setLegalName(profile.hasNonNull("businessName") ? text(profile, "businessName")
				: profile.hasNonNull("registeredBusinessName") ? text(profile, "registeredBusinessName") : null);
		if (w.getLegalName() != null && w.getLegalName().codePointCount(0, w.getLegalName().length()) > 255) {
			throw bad("BUSINESS_NAME_TOO_LONG");
		}
		w.setProfileJson(profile.isObject() ? p.write(profile) : null);
	}

	private boolean applicationDetailsReady(WorkflowRow w) {
		if (w.getPurpose() == null || w.getLegalName() == null || w.getProfileJson() == null)
			return false;
		try {
			var d = parse(w.getProfileJson()).path("phase0");
			var birth = LocalDate.parse(d.path("dateOfBirth").asText());
			return d.isObject() && d.path("detailsConfirmed").asBoolean(false)
					&& d.path("kycTermsAccepted").asBoolean(false) && "MOCK_ID".equals(d.path("documentType").asText())
					&& d.path("documentReference").asText().matches("TEST-[A-Z0-9-]{6,40}")
					&& !birth.isAfter(LocalDate.now(ZoneOffset.UTC).minusYears(18))
					&& d.path("preferredTenureMonths").asInt() >= 1 && d.path("preferredTenureMonths").asInt() <= 120;
		} catch (RuntimeException missing) {
			return false;
		}
	}

	private void consent(WorkflowRow w, JsonNode d, Actor origin, Event cause) {
		int version = positive(d, "consentVersion");
		UUID consentId = uuid(d, "consentId");
		if (version < w.getConsentVersion()) {
			return;
		}
		JsonNode normalized = p.json(data("consentId", consentId, "consentVersion", version, "status",
				text(d, "status"), "scopes", d.path("scopes"), "sourceMarketplaceIds", d.path("sourceMarketplaceIds"),
				"recipientLenderIds", d.path("recipientLenderIds"), "purpose", d.path("purpose"), "validityPolicy",
				d.path("validityPolicy")));
		if (version == w.getConsentVersion()) {
			if (!Objects.equals(w.getConsentId(), consentId)
					|| !Objects.equals(w.getConsentJson(), p.write(normalized))) {
				throw conflict("CONSENT_VERSION_CONFLICT");
			}
			return;
		}
		boolean previouslyAllowed = operationalSharingAllowed(w);
		w.setConsentId(consentId);
		w.setConsentVersion(version);
		w.setConsentJson(p.write(normalized));
		String status = oneOf(d, "status", Set.of("GRANTED", "DECLINED", "REVOKED"));
		w.setConsentGranted(status.equals("GRANTED") && contains(d.path("scopes"), "MARKETPLACE_DATA_READ")
				&& contains(d.path("scopes"), "IDENTITY_VERIFICATION")
				&& contains(d.path("scopes"), "LENDER_APPLICATION_SHARE")
				&& contains(d.path("sourceMarketplaceIds"), w.getMarketplaceId().toString())
				&& "APPLICATION_OPEN".equals(d.path("validityPolicy").asText()) && !d.path("purpose").asText().isBlank()
				&& (w.getLenderId() == null || contains(d.path("recipientLenderIds"), w.getLenderId())));
		if (!w.isConsentGranted()) {
			w.setDispatchAuthorized(false);
		}
		if (!status.equals("GRANTED")) {
			if (!TERMINAL.contains(w.getStatus())) {
				w.setDetail("CONSENT_" + status);
				move(w, "ACTION_REQUIRED", p.systemActor(), origin, cause);
			}

			return;
		}
		if (TERMINAL.contains(w.getStatus())) {
			return;
		}
		if (!w.isConsentGranted()) {
			if (previouslyAllowed || w.getSubmissionId() != null) {
				w.setDetail("CONSENT_SCOPE_OR_RECIPIENT_REMOVED");
				move(w, "ACTION_REQUIRED", p.systemActor(), origin, cause);
				return;
			}
			w.setDetail("REQUIRED_CONSENT_INCOMPLETE");
			changed(w, p.systemActor(), origin, cause);
			return;
		}
		if (w.getStatus().equals("ACTION_REQUIRED") && Objects.toString(w.getDetail(), "").startsWith("CONSENT_")) {
			if (w.getSubmissionId() == null)
				startVerification(w, origin, cause);
			else {
				w.setDispatchAuthorized(true);
				boolean activeOffer = repository.offersForApplicationQuery().setParameter("a", w.getId())
						.getResultList().stream()
						.anyMatch(o -> "ACTIVE".equals(o.getStatus()) && o.getExpiresAt().isAfter(Instant.now()));
				w.setDetail(null);
				move(w, activeOffer ? "APPROVED"
						: required(SubmissionRow.class, w.getSubmissionId()).getExternalId() == null ? "SUBMITTING"
								: "UNDER_REVIEW",
						p.systemActor(), origin, cause);
			}
			return;
		}
		if (w.getStatus().equals("DRAFT")) {
			startVerification(w, origin, cause);
		} else if (w.getStatus().equals("READY_FOR_LENDER")) {
			dispatch(w, origin, cause);
		}
	}

	private void startVerification(WorkflowRow w, Actor origin, Event cause) {
		if (!w.isConsentGranted() || w.isRevisionPending()) {
			throw conflict("WORKFLOW_GATED");
		}
		String missingContext = !applicationDetailsReady(w) ? "APPLICATION_DETAILS_REQUIRED"
				: w.getSellerId() == null ? "TRUSTED_SELLER_MAPPING_REQUIRED" : null;
		if (missingContext != null) {
			w.setDetail(missingContext);
			if (w.getStatus().equals("ACTION_REQUIRED"))
				changed(w, p.systemActor(), origin, cause);
			else if (w.getStatus().equals("DRAFT")) {
				w.setStatus("ACTION_REQUIRED");
				changed(w, p.systemActor(), origin, cause, "DRAFT");
			}
			return;
		}
		retirePreviousCheckIssues(w);
		w.setVerificationId(UUID.randomUUID());
		w.setSnapshotId(null);
		w.setEvaluationId(null);
		w.setEligibilityId(null);
		w.setEvaluationAsOfDate(null);
		w.setAuthorisedRetrievalReference(null);
		w.setEligibilityOperationId(null);
		w.setEligibilityStatusUrl(null);
		w.setDetail(null);
		move(w, "SUBMITTED", p.systemActor(), origin, cause);
		p.job(w.getVerificationId(), "consent", verificationTriggerPath,
				data("verificationId", w.getVerificationId(), "applicationId", w.getId(), "applicationRevision",
						w.getRevision(), "consentId", w.getConsentId(), "consentVersion", w.getConsentVersion(),
						"originActor", origin),
				w.getVerificationId().toString(), w.getId(), origin);
	}

	private void verification(WorkflowRow w, JsonNode d, Actor origin, Event e) {
		if (!Objects.equals(w.getVerificationId(), uuid(d, "verificationId"))) {
			return;
		}
		if (!w.getStatus().equals("SUBMITTED")) {
			return;
		}
		String status = oneOf(d, "status", Set.of("VERIFIED", "FAILED", "PENDING", "ACTION_REQUIRED"));
		w.setDetail(d.path("reasonCode").asText(null));
		if (status.equals("PENDING")) {
			return;
		}
		resolveJob(w, w.getVerificationId());
		if (status.equals("FAILED")) {
			move(w, "ACTION_REQUIRED", e.actor(), origin, e);
			return;
		}
		if (status.equals("ACTION_REQUIRED")) {
			move(w, "ACTION_REQUIRED", e.actor(), origin, e);
			return;
		}
		// Only the trusted Consent & Verification producer can attest this reference.
		// Initial application-sharing consent does not authorise financial retrieval.
		String retrievalReference = optionalText(d, "authorisedRetrievalReference", 255);
		if (retrievalReference == null) {
			w.setDetail("FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED");
			move(w, "ACTION_REQUIRED", e.actor(), origin, e);
			return;
		}
		// Eligibility owns the immutable snapshot and returns its ID with the result.
		w.setSnapshotId(null);
		w.setEvaluationId(UUID.randomUUID());
		w.setEvaluationAsOfDate(LocalDate.now(ZoneOffset.UTC));
		w.setAuthorisedRetrievalReference(retrievalReference);
		move(w, "ELIGIBILITY_CHECKING", e.actor(), origin, e);
		p.job(w.getEvaluationId(), "eligibility", "/internal/v1/eligibility/evaluations",
				data("evaluationRequestId", w.getEvaluationId(), "applicationId", w.getId(), "applicationRevision",
						w.getRevision(), "verificationId", w.getVerificationId(), "asOfDate",
						w.getEvaluationAsOfDate().toString(), "authorisedRetrievalReference",
						w.getAuthorisedRetrievalReference()),
				w.getEvaluationId().toString(), w.getId(), origin);
	}

	private void eligibility(WorkflowRow w, JsonNode d, Actor origin, Event e) {
		if (!Objects.equals(w.getVerificationId(), uuid(d, "verificationId"))
				|| !Objects.equals(w.getEvaluationId(), uuid(d, "evaluationRequestId"))) {
			return;
		}
		if (w.getEligibilityId() != null) {
			if (!Objects.equals(w.getEligibilityId(), uuid(d, "eligibilityResultId"))
					|| !Objects.equals(w.getSnapshotId(), uuid(d, "financialSnapshotId"))
					|| !parse(w.getEligibilityJson()).equals(d)) {
				throw conflict("IMMUTABLE_ELIGIBILITY_RESULT_CONFLICT");
			}
			return;
		}
		if (!w.getStatus().equals("ELIGIBILITY_CHECKING")) {
			return;
		}
		if (!w.getId().equals(uuid(d, "applicationId")))
			throw bad("ELIGIBILITY_APPLICATION_ID_MISMATCH");
		LocalDate asOfDate = date(d, "asOfDate");
		if (!Objects.equals(w.getEvaluationAsOfDate(), asOfDate))
			throw bad("ELIGIBILITY_AS_OF_DATE_MISMATCH");
		if (money(d, "requestedAmount").compareTo(w.getAmount()) != 0
				|| !oneOf(d, "currency", Set.of("INR")).equals(w.getCurrency())) {
			throw bad("ELIGIBILITY_APPLICATION_INPUT_MISMATCH");
		}
		UUID resultId = uuid(d, "eligibilityResultId");
		UUID snapshotId = uuid(d, "financialSnapshotId");
		text(d, "calculationVersion");
		timestamp(d, "evaluatedAt");
		stringArray(d, "reasonCodes");
		String outcome = oneOf(d, "outcome", Set.of("EVALUATED", "ACTION_REQUIRED"));
		if (!d.path("lenderResults").isArray()) {
			throw bad("LENDER_RESULTS_REQUIRED");
		}
		if (outcome.equals("ACTION_REQUIRED")) {
			if (!d.path("lenderResults").isEmpty()) {
				throw bad("ACTION_REQUIRED_MUST_NOT_HAVE_LENDER_RESULTS");
			}
			Set<String> financialReasons = Set.of("FINANCIAL_DATA_INCOMPLETE", "FINANCIAL_DATA_INCONSISTENT",
					"FINANCIAL_DATA_INSUFFICIENT", "CREDIT_INFORMATION_REQUIRED");
			for (JsonNode reason : d.path("reasonCodes")) {
				if (!financialReasons.contains(reason.asText()))
					throw bad("INVALID_FINANCIAL_ACTION_REASON");
			}
			for (String summary : List.of("averageMonthlyNetSales", "averageMonthlyPayout", "returnRatePercent")) {
				if (d.has(summary))
					money(d, summary);
			}
			if (contains(d.path("reasonCodes"), "CREDIT_INFORMATION_REQUIRED")) {
				w.setDetail("CREDIT_INFORMATION_REQUIRED");
			} else if (contains(d.path("reasonCodes"), "FINANCIAL_DATA_INCOMPLETE")) {
				w.setDetail("FINANCIAL_DATA_INCOMPLETE");
			} else if (contains(d.path("reasonCodes"), "FINANCIAL_DATA_INCONSISTENT")) {
				w.setDetail("FINANCIAL_DATA_INCONSISTENT");
			} else if (contains(d.path("reasonCodes"), "FINANCIAL_DATA_INSUFFICIENT")) {
				w.setDetail("FINANCIAL_DATA_INSUFFICIENT");
			} else {
				throw bad("INVALID_FINANCIAL_ACTION_REASON");
			}
			bindEligibility(w, d, snapshotId, resultId);
			move(w, "ACTION_REQUIRED", e.actor(), origin, e);
			return;
		}
		List<JsonNode> candidates = new ArrayList<>();
		Set<String> products = new HashSet<>();
		money(d, "averageMonthlyNetSales");
		money(d, "averageMonthlyPayout");
		if (money(d, "returnRatePercent").compareTo(new BigDecimal("100.00")) > 0) {
			throw bad("INVALID_returnRatePercent");
		}
		if (d.path("lenderResults").isEmpty()) {
			throw bad("EVALUATED_REQUIRES_LENDER_RESULTS");
		}
		for (JsonNode candidate : d.path("lenderResults")) {
			UUID lenderId = uuid(candidate, "lenderId");
			UUID productId = uuid(candidate, "productId");
			text(candidate, "ruleVersion");
			stringArray(candidate, "reasonCodes");
			if (!products.add(lenderId + ":" + productId) || !candidate.path("eligible").isBoolean()
					|| !candidate.path("routingPriority").canConvertToInt()
					|| !candidate.path("routingPriority").isIntegralNumber()
					|| candidate.path("routingPriority").asInt() < 0) {
				throw bad("INVALID_LENDER_RESULT");
			}
			BigDecimal limit = money(candidate, "indicativeMaxAmount");
			if (candidate.path("eligible").asBoolean() && limit.compareTo(w.getAmount()) >= 0) {
				uuid(candidate, "lenderId");
				uuid(candidate, "productId");
				if (!candidate.path("routingPriority").canConvertToInt()
						|| candidate.path("routingPriority").asInt() < 0) {
					throw bad("INVALID_ROUTING_PRIORITY");
				}
				candidates.add(candidate);
			}
		}
		candidates.sort(Comparator.comparingInt((JsonNode candidate) -> candidate.path("routingPriority").asInt())
				.thenComparing(candidate -> candidate.path("productId").asText()));
		bindEligibility(w, d, snapshotId, resultId);
		if (candidates.isEmpty()) {
			move(w, "NOT_ELIGIBLE", e.actor(), origin, e);
			return;
		}
		JsonNode chosen = candidates.getFirst();
		w.setLenderId(text(chosen, "lenderId"));
		w.setProductId(text(chosen, "productId"));
		move(w, "READY_FOR_LENDER", e.actor(), origin, e);
		dispatch(w, origin, e);
	}

	private void bindEligibility(WorkflowRow w, JsonNode data, UUID snapshotId, UUID resultId) {
		w.setSnapshotId(snapshotId);
		w.setEligibilityId(resultId);
		w.setEligibilityJson(p.write(data));
		resolveJob(w, w.getEvaluationId());
	}

	private void dispatch(WorkflowRow w, Actor origin, Event cause) {
		if (w.getSubmissionId() != null || !w.getStatus().equals("READY_FOR_LENDER") || w.isRevisionPending()) {
			return;
		}
		JsonNode consent = parse(w.getConsentJson());
		if (!w.isConsentGranted() || !contains(consent.path("recipientLenderIds"), w.getLenderId())) {
			w.setDetail("SELECTED_LENDER_REQUIRES_CONSENT");
			changed(w, p.systemActor(), origin, cause);
			return;
		}
		w.setSubmissionId(UUID.randomUUID());
		w.setDispatchAuthorized(true);
		SubmissionRow submission = new SubmissionRow();
		submission.setId(w.getSubmissionId());
		submission.setApplicationId(w.getId());
		submission.setLenderId(w.getLenderId());
		submission.setProductId(w.getProductId());
		submission.setRevision(w.getRevision());
		submission.setStatus("CREATED");
		submission.setCreatedAt(Instant.now());
		repository.save(submission);
		move(w, "SUBMITTING", p.systemActor(), origin, cause);
		p.job(submission.getId(), "integration", "/api/v1/internal/submissions",
				data("submissionId", submission.getId(), "applicationId", w.getId(), "applicationRevision",
						w.getRevision(), "eligibilityResultId", w.getEligibilityId(), "financialSnapshotId",
						w.getSnapshotId(), "verificationId", w.getVerificationId(), "consentId", w.getConsentId(),
						"consentVersion", w.getConsentVersion(), "lenderId", w.getLenderId(), "productId",
						w.getProductId(), "sellerId", w.getSellerId(), "marketplaceSellerId",
						w.getMarketplaceSellerId(), "sellerSubject", w.getSellerSubject(), "registeredBusinessName",
						w.getLegalName(), "preferredTenureMonths",
						parse(w.getProfileJson()).path("phase0").path("preferredTenureMonths").asInt(), "kycStatus",
						"VERIFIED", "eligibilitySummary", lenderSummary(w), "requestedAmount",
						w.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency",
						w.getCurrency(), "loanPurpose", w.getPurpose(), "originActor", origin),
				submission.getId().toString(), w.getId(), origin);
	}

	public java.util.List<UUID> pendingFinancialPermissions() {
		return repository.openFinancialPermissionsQuery().getResultList().stream().map(WorkflowRow::getId).toList();
	}

	public void reconcileFinancialPermission(UUID applicationId) {
		WorkflowRow w = locked(applicationId);
		if (!TERMINAL.contains(w.getStatus()))
			applyFinancialPermission(w, currentFinancialPermission(w), null);
	}

	public JsonNode authorization(UUID submissionId, Jwt jwt) {
		p.machine(jwt, "workflow.internal", integrationClient);
		SubmissionRow submission = required(SubmissionRow.class, submissionId);
		WorkflowRow w = locked(submission.getApplicationId());
		return p.json(data("closed", TERMINAL.contains(w.getStatus()), "authorized",
				w.isDispatchAuthorized() && sharingAllowed(w) && !TERMINAL.contains(w.getStatus())
						&& !w.isRevisionPending() && w.getRevision() == submission.getRevision(),
				"applicationId", w.getId(), "applicationRevision", w.getRevision(), "consentId", w.getConsentId(),
				"consentVersion", w.getConsentVersion()));
	}

	private SubmissionRow binding(WorkflowRow w, JsonNode d) {
		if (!Objects.equals(w.getSubmissionId(), uuid(d, "submissionId"))
				|| !Objects.equals(w.getLenderId(), text(d, "lenderId"))
				|| w.getRevision() != positive(d, "applicationRevision")) {
			throw bad("LENDER_RESULT_BINDING_MISMATCH");
		}
		return required(SubmissionRow.class, w.getSubmissionId());
	}

	private SubmissionRow receipt(WorkflowRow w, JsonNode d) {
		SubmissionRow submission = binding(w, d);
		String external = text(d, "lenderApplicationId");
		if (submission.getExternalId() != null && !submission.getExternalId().equals(external)) {
			throw bad("LENDER_EXTERNAL_ID_MISMATCH");
		}
		submission.setExternalId(external);
		resolveOperation(w, "SUBMIT", d);
		if (submission.getStatus() == null || submission.getStatus().equals("CREATED")
				|| submission.getStatus().equals("SENDING")) {
			submission.setStatus("SUBMITTED");
		}
		return submission;
	}

	private SubmissionRow matching(WorkflowRow w, JsonNode d) {
		SubmissionRow submission = binding(w, d);
		if (submission.getExternalId() == null) {
			throw new Platform.Pending("LENDER_RECEIPT_PENDING");
		}
		if (!submission.getExternalId().equals(text(d, "lenderApplicationId"))) {
			throw bad("LENDER_EXTERNAL_ID_MISMATCH");
		}
		return submission;
	}

	private void childBinding(WorkflowRow w, UUID applicationId, UUID submissionId) {
		if (!Objects.equals(w.getId(), applicationId) || !Objects.equals(w.getSubmissionId(), submissionId)) {
			throw bad("LENDER_RESULT_CHILD_BINDING_MISMATCH");
		}
	}

	private boolean operationalSharingAllowed(WorkflowRow w) {
		return w.isConsentGranted() && (w.getLenderId() == null
				|| contains(parse(w.getConsentJson()).path("recipientLenderIds"), w.getLenderId()));
	}

	private JsonNode currentFinancialPermission(WorkflowRow w) {
		JsonNode response = p
				.call("consent", org.springframework.http.HttpMethod.GET,
						consentBase.replaceAll("/+$", "") + "/internal/v1/applications/" + w.getId()
								+ "/financial-permission?revision=" + w.getRevision(),
						null, null, w.getId().toString());
		if (response == null || !w.getId().toString().equals(response.path("applicationId").asText())
				|| w.getRevision() != response.path("applicationRevision").asInt())
			throw new Platform.Pending("FINANCIAL_PERMISSION_UNAVAILABLE");
		return response;
	}

	private boolean activeFinancialPermission(WorkflowRow w, JsonNode permission) {
		if (!"ACTIVE".equals(permission.path("status").asText())
				|| permission.path("operationalConsentVersion").asInt() != w.getConsentVersion())
			return false;
		try {
			return Instant.parse(permission.path("expiresAt").asText()).isAfter(Instant.now());
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	private boolean sharingAllowed(WorkflowRow w) {
		return operationalSharingAllowed(w) && activeFinancialPermission(w, currentFinancialPermission(w));
	}

	private boolean financialPause(WorkflowRow w) {
		return w.getFinancialPermissionJson() != null
				&& parse(w.getFinancialPermissionJson()).hasNonNull("resumeStatus");
	}

	void applyFinancialPermission(WorkflowRow w, JsonNode permission, Event cause) {
		if (TERMINAL.contains(w.getStatus()) || w.isRevisionPending())
			return;
		ObjectNode snapshot = (ObjectNode) permission.deepCopy();
		JsonNode previous = w.getFinancialPermissionJson() == null ? p.json(data())
				: parse(w.getFinancialPermissionJson());
		if (!activeFinancialPermission(w, permission)) {
			String reason = "FINANCIAL_CONSENT_" + switch (permission.path("status").asText()) {
			case "EXPIRED" -> "EXPIRED";
			case "REVOKED" -> "WITHDRAWN";
			default -> "REQUIRED";
			};
			// An ungranted draft is already waiting for consent; don't start verification
			// on its behalf.
			if (w.getStatus().equals("DRAFT")) {
				w.setFinancialPermissionJson(p.write(snapshot));
				return;
			}
			boolean alreadyPaused = w.getStatus().equals("ACTION_REQUIRED") && financialPause(w);
			snapshot.put("resumeStatus", alreadyPaused ? previous.path("resumeStatus").asText() : w.getStatus());
			String resumeReason = alreadyPaused ? previous.path("resumeReason").asText(null) : w.getDetail();
			if (resumeReason != null)
				snapshot.put("resumeReason", resumeReason);
			w.setFinancialPermissionJson(p.write(snapshot));
			w.setDispatchAuthorized(false);
			if (!alreadyPaused || !reason.equals(w.getDetail())) {
				w.setDetail(reason);
				move(w, "ACTION_REQUIRED", p.systemActor(), cause == null ? p.systemActor() : origin(cause), cause);
			}
			return;
		}
		if (financialPause(w) && !operationalSharingAllowed(w)) {
			snapshot.set("resumeStatus", previous.path("resumeStatus"));
			if (previous.has("resumeReason"))
				snapshot.set("resumeReason", previous.path("resumeReason"));
		}
		boolean resume = financialPause(w) && w.getStatus().equals("ACTION_REQUIRED") && operationalSharingAllowed(w);
		w.setFinancialPermissionJson(p.write(snapshot));
		if (resume) {
			Actor actor = cause == null ? p.systemActor() : origin(cause);
			if (w.getSubmissionId() == null) {
				startVerification(w, actor, cause);
				return;
			}
			String status = previous.path("resumeStatus").asText("UNDER_REVIEW");
			if (!Set.of("APPROVED", "ACTION_REQUIRED", "UNDER_REVIEW", "MANUAL_REVIEW", "SUBMITTING").contains(status))
				status = "UNDER_REVIEW";
			w.setDispatchAuthorized(true);
			w.setDetail(previous.path("resumeReason").asText(null));
			move(w, status, p.systemActor(), actor, cause);
		}
	}

	private void processingFailure(WorkflowRow w, JsonNode d, Event e) {
		SubmissionRow submission = binding(w, d);
		String operation = text(d, "operation");
		if (operation.equals("SUBMIT") && submission.getExternalId() != null) {
			return;
		}
		if (operation.equals("CANCEL") && "ACKNOWLEDGED".equals(submission.getCancellationAcknowledgement())) {
			return;
		}
		if (operation.equals("ANSWER_INFORMATION") && d.hasNonNull("informationRequestId")) {
			InformationRow question = repository.find(InformationRow.class, uuid(d, "informationRequestId"));
			if (question != null) {
				childBinding(w, question.getApplicationId(), question.getSubmissionId());
			}
			if (question != null && "ACKNOWLEDGED".equals(question.getAcknowledgement())) {
				return;
			}
		}
		if (operation.equals("ACCEPT_OFFER") && d.hasNonNull("offerId")) {
			OfferRow offer = repository.find(OfferRow.class, uuid(d, "offerId"));
			if (offer != null) {
				childBinding(w, offer.getApplicationId(), offer.getSubmissionId());
			}
			if (offer != null && "ACKNOWLEDGED".equals(offer.getAcknowledgement())) {
				return;
			}
		}
		UUID taskId = uuid(d, "taskId");
		technical(w, "task:" + taskId, operation, d, false,
				d.path("outcomeUnknown").asBoolean() ? "OUTCOME_UNKNOWN" : "MANUAL_INTERVENTION_REQUIRED",
				text(d, "failureCode"));
		changed(w, e.actor(), origin(e), e);
	}

	private void processingRecovered(WorkflowRow w, JsonNode d, Event e) {
		binding(w, d);
		String operation = text(d, "operation");
		UUID taskId = uuid(d, "taskId");
		technical(w, "task:" + taskId, operation, d, true, "OK", null);
		changed(w, e.actor(), origin(e), e);
	}

	@Override
	public boolean canRunJob(UUID applicationId, UUID jobId, Job job) {
		WorkflowRow w = repository.find(WorkflowRow.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
		if (w == null)
			return false;
		JsonNode d = job.payload();
		if (d.hasNonNull("applicationRevision") && d.path("applicationRevision").asInt() != w.getRevision())
			return false;
		if (job.target().equals("integration") && job.path().endsWith("/cancellation")) {
			return Objects.equals(jobId, w.getCancellationId())
					&& Objects.equals(w.getSubmissionId(), uuid(d, "submissionId"));
		}
		if (TERMINAL.contains(w.getStatus()) || w.isRevisionPending() || !sharingAllowed(w))
			return false;
		if (job.target().equals("consent")) {
			return Objects.equals(jobId, w.getVerificationId()) && w.getStatus().equals("SUBMITTED");
		}
		if (job.target().equals("eligibility")) {
			return Objects.equals(jobId, w.getEvaluationId()) && w.getStatus().equals("ELIGIBILITY_CHECKING")
					&& w.getEligibilityId() == null && w.getEvaluationAsOfDate() != null
					&& Objects.equals(w.getEvaluationAsOfDate().toString(), d.path("asOfDate").asText())
					&& w.getAuthorisedRetrievalReference() != null && Objects.equals(
							w.getAuthorisedRetrievalReference(), d.path("authorisedRetrievalReference").asText());
		}
		if (job.target().equals("integration") && job.path().equals("/api/v1/internal/submissions")) {
			return Objects.equals(jobId, w.getSubmissionId()) && w.isDispatchAuthorized()
					&& w.getStatus().equals("SUBMITTING");
		}
		if (job.target().equals("integration") && job.path().endsWith("/information-responses")) {
			InformationRow q = repository.find(InformationRow.class, uuid(d, "informationRequestId"));
			return q != null && Objects.equals(q.getApplicationId(), w.getId())
					&& Objects.equals(q.getSubmissionId(), w.getSubmissionId()) && q.getStatus().equals("OPEN")
					&& q.getAnswersJson() != null && !"ACKNOWLEDGED".equals(q.getAcknowledgement())
					&& w.getStatus().equals("ACTION_REQUIRED");
		}
		return false;
	}

	/**
	 * Observe durable HTTP delivery inside the same transaction as its state
	 * change.
	 */
	@Override
	public void onJobResponse(UUID applicationId, UUID jobId, Job job, JsonNode response) {
		if (!job.target().equals("eligibility"))
			return;
		WorkflowRow w = repository.find(WorkflowRow.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
		if (w == null || !Objects.equals(w.getEvaluationId(), jobId)
				|| job.payload().path("applicationRevision").asInt() != w.getRevision()
				|| !Objects.equals(w.getVerificationId(), uuid(job.payload(), "verificationId")))
			return;
		if (response == null || !response.isObject())
			throw bad("INVALID_ELIGIBILITY_OPERATION_RESPONSE");
		UUID operation = uuid(response, "operationId");
		String statusUrl = text(response, "statusUrl");
		if (!"PENDING".equals(text(response, "status")) || !("/api/v1/operations/" + operation).equals(statusUrl)) {
			throw bad("INVALID_ELIGIBILITY_OPERATION_RESPONSE");
		}
		if (w.getEligibilityOperationId() != null && (!w.getEligibilityOperationId().equals(operation)
				|| !Objects.equals(w.getEligibilityStatusUrl(), statusUrl))) {
			throw conflict("ELIGIBILITY_OPERATION_REFERENCE_CONFLICT");
		}
		boolean changed = w.getEligibilityOperationId() == null;
		w.setEligibilityOperationId(operation);
		w.setEligibilityStatusUrl(statusUrl);
		if (changed)
			changed(w, p.systemActor(), job.origin(), p.cause(job.payload()));
	}

	/**
	 * Observe durable HTTP delivery inside the same transaction as its state
	 * change.
	 */
	@Override
	public void onJobState(UUID applicationId, UUID jobId, Job job, String state, String error) {
		WorkflowRow w = repository.find(WorkflowRow.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
		if (w == null) {
			return;
		}
		JsonNode d = job.payload();
		if (d.hasNonNull("applicationRevision") && d.path("applicationRevision").asInt() != w.getRevision()) {
			return;
		}
		String operation;
		if (job.target().equals("consent") && Objects.equals(jobId, w.getVerificationId())) {
			operation = "VERIFICATION";
			if (!w.getStatus().equals("SUBMITTED"))
				state = "DONE";
		} else if (job.target().equals("eligibility") && Objects.equals(jobId, w.getEvaluationId())) {
			operation = "EVALUATION";
			if (w.getEligibilityId() != null)
				state = "DONE";
		} else if (job.target().equals("integration") && job.path().equals("/api/v1/internal/submissions")
				&& Objects.equals(jobId, w.getSubmissionId())) {
			operation = "SUBMIT";
			SubmissionRow s = repository.find(SubmissionRow.class, w.getSubmissionId());
			if (s != null && s.getExternalId() != null)
				state = "DONE";
		} else if (job.target().equals("integration") && job.path().endsWith("/information-responses")) {
			operation = "ANSWER_INFORMATION";
			InformationRow q = d.hasNonNull("informationRequestId")
					? repository.find(InformationRow.class, uuid(d, "informationRequestId"))
					: null;
			if (q == null || !Objects.equals(q.getApplicationId(), w.getId())
					|| !Objects.equals(q.getSubmissionId(), w.getSubmissionId()))
				return;
			if (!"OPEN".equals(q.getStatus()) || "ACKNOWLEDGED".equals(q.getAcknowledgement()))
				state = "DONE";
		} else if (job.target().equals("integration") && job.path().endsWith("/cancellation")
				&& Objects.equals(jobId, w.getCancellationId())) {
			operation = "CANCEL";
			SubmissionRow s = repository.find(SubmissionRow.class, w.getSubmissionId());
			if (s != null && "ACKNOWLEDGED".equals(s.getCancellationAcknowledgement()))
				state = "DONE";
		} else {
			return;
		}
		boolean resolved = state.equals("DONE") || state.equals("SKIPPED");
		technical(w, "job:" + jobId, operation, d, resolved,
				state.equals("DEAD") ? "MANUAL_INTERVENTION_REQUIRED" : "RETRY_PENDING", error);
		changed(w, p.systemActor(), job.origin(), p.cause(job.payload()));
	}

	private ObjectNode issues(WorkflowRow w) {
		return w.getHealthIssuesJson() == null ? (ObjectNode) p.json(Map.of())
				: (ObjectNode) parse(w.getHealthIssuesJson()).deepCopy();
	}

	private void technical(WorkflowRow w, String key, String operation, JsonNode binding, boolean resolved,
			String health, String code) {
		ObjectNode issues = issues(w);
		JsonNode previous = issues.path(key);
		// A successful task remains a tombstone: delayed failure cannot reopen it.
		if (!resolved && previous.path("resolved").asBoolean())
			return;
		issues.set(key,
				p.json(data("operation", operation, "applicationRevision", w.getRevision(), "resolved", resolved,
						"processingHealth", resolved ? "OK" : health, "failureCode", code, "informationRequestId",
						optionalNode(binding, "informationRequestId"), "offerId", optionalNode(binding, "offerId"),
						"acceptanceId", optionalNode(binding, "acceptanceId"), "cancellationId",
						optionalNode(binding, "cancellationId"))));
		saveIssues(w, issues);
	}

	private void resolveJob(WorkflowRow w, UUID jobId) {
		if (jobId == null)
			return;
		ObjectNode issues = issues(w);
		JsonNode issue = issues.path("job:" + jobId);
		if (issue.isObject())
			((ObjectNode) issue).put("resolved", true);
		saveIssues(w, issues);
	}

	private void resolveOperation(WorkflowRow w, String operation, JsonNode d) {
		ObjectNode issues = issues(w);
		issues.elements().forEachRemaining(issue -> {
			if (!issue.path("operation").asText().equals(operation))
				return;
			for (String identity : List.of("informationRequestId", "offerId", "acceptanceId", "cancellationId")) {
				if (issue.hasNonNull(identity) && !issue.path(identity).equals(d.path(identity)))
					return;
			}
			((ObjectNode) issue).put("resolved", true);
		});
		saveIssues(w, issues);
	}

	private void retirePreviousCheckIssues(WorkflowRow w) {
		ObjectNode issues = issues(w);
		// Refresh/correction replaces only check jobs; lender tasks keep their own
		// identity.
		issues.elements().forEachRemaining(issue -> {
			if (Set.of("VERIFICATION", "EVALUATION").contains(issue.path("operation").asText())) {
				((ObjectNode) issue).put("resolved", true);
			}
		});
		saveIssues(w, issues);
	}

	private void saveIssues(WorkflowRow w, ObjectNode issues) {
		// Earlier revisions are ignored by every result/job guard, so their tombstones
		// can be removed.
		List<String> obsolete = new ArrayList<>();
		issues.fields().forEachRemaining(entry -> {
			if (entry.getValue().path("applicationRevision").asInt() < w.getRevision())
				obsolete.add(entry.getKey());
		});
		issues.remove(obsolete);
		w.setHealthIssuesJson(p.write(issues));
		Set<String> active = new HashSet<>();
		issues.elements().forEachRemaining(issue -> {
			if (!issue.path("resolved").asBoolean())
				active.add(issue.path("processingHealth").asText());
		});
		w.setHealth(active.contains("OUTCOME_UNKNOWN") ? "OUTCOME_UNKNOWN"
				: active.contains("MANUAL_INTERVENTION_REQUIRED") ? "MANUAL_INTERVENTION_REQUIRED"
						: active.contains("RETRY_PENDING") ? "RETRY_PENDING" : "OK");
	}

	private void lenderReady(WorkflowRow w) {
		if (w.getStatus().equals("SUBMITTING")) {
			throw new Platform.Pending("LENDER_RECEIPT_PENDING");
		}
	}

	private void informationRequested(WorkflowRow w, JsonNode d, Event e) {
		SubmissionRow submission = matching(w, d);
		if (w.getStatus().equals("SUBMITTING")) {
			throw new Platform.Pending("LENDER_RECEIPT_PENDING");
		}
		UUID id = uuid(d, "informationRequestId");
		JsonNode questions = d.path("questions");
		if (!questions.isArray() || questions.isEmpty()) {
			throw bad("QUESTIONS_REQUIRED");
		}
		Set<String> ids = new HashSet<>();
		for (JsonNode question : questions) {
			if (!ids.add(text(question, "questionId")) || text(question, "prompt").length() > 1000) {
				throw bad("INVALID_QUESTIONS");
			}
		}
		InformationRow row = repository.find(InformationRow.class, id);
		if (row != null) {
			childBinding(w, row.getApplicationId(), row.getSubmissionId());
			if (!row.getQuestionsJson().equals(p.write(questions))) {
				throw conflict("QUESTION_ID_CONFLICT");
			}
			return;
		}
		row = new InformationRow();
		row.setId(id);
		row.setApplicationId(w.getId());
		row.setSubmissionId(submission.getId());
		row.setQuestionsJson(p.write(questions));
		row.setStatus("OPEN");
		row.setCreatedAt(Instant.now());
		repository.save(row);
		submission.setStatus("ACTION_REQUIRED");
		move(w, "ACTION_REQUIRED", e.actor(), origin(e), e);
	}

	private void informationAcknowledged(WorkflowRow w, JsonNode d, Event e) {
		SubmissionRow submission = matching(w, d);
		InformationRow question = repository.find(InformationRow.class, uuid(d, "informationRequestId"));
		if (question == null) {
			throw new Platform.Pending("ANSWERS_NOT_COMMITTED");
		}
		childBinding(w, question.getApplicationId(), question.getSubmissionId());
		if ("CANCELLED".equals(question.getStatus()) || "ACKNOWLEDGED".equals(question.getAcknowledgement())) {
			resolveOperation(w, "ANSWER_INFORMATION", d);
			changed(w, e.actor(), origin(e), e);
			return;
		}
		if (question.getAnswersJson() == null) {
			throw new Platform.Pending("ANSWERS_NOT_COMMITTED");
		}
		question.setStatus("ANSWERED");
		question.setAcknowledgement("ACKNOWLEDGED");
		question.incrementVersion();
		resolveOperation(w, "ANSWER_INFORMATION", d);
		repository.flush();
		if (w.getStatus().equals("ACTION_REQUIRED") && !financialPause(w)
				&& repository.countOpenInformationRequestsQuery().setParameter("a", w.getId()).getSingleResult() == 0) {
			submission.setStatus("SUBMITTED");
			move(w, "UNDER_REVIEW", e.actor(), origin(e), e);
		}
	}

	private void decision(WorkflowRow w, JsonNode d, Event e) {
		SubmissionRow submission = matching(w, d);
		if (w.getStatus().equals("SUBMITTING")) {
			throw new Platform.Pending("LENDER_RECEIPT_PENDING");
		}
		uuid(d, "decisionId");
		timestamp(d, "decidedAt");
		String decision = oneOf(d, "decision", Set.of("APPROVED", "REJECTED", "ACTION_REQUIRED", "MANUAL_REVIEW"));
		if (decision.equals("REJECTED")) {
			if (!text(d, "reasonCode").matches("[A-Z][A-Z0-9_]{0,99}"))
				throw bad("INVALID_REJECTION_REASON_CODE");
		}
		submission.setStatus(decision);
		w.setDecisionJson(p.write(d));
		switch (decision) {
		case "REJECTED" -> {
			w.setDetail(text(d, "reasonCode"));
			move(w, "REJECTED", e.actor(), origin(e), e);
		}
		case "MANUAL_REVIEW", "ACTION_REQUIRED" -> {
			w.setDetail("LENDER_INFORMATION_REQUIRED");
			if (d.path("questions").isArray() && !d.path("questions").isEmpty())
				informationRequested(w, d, e);
			else {
				w.setDetail("AWAITING_QUESTION_DETAILS");
				move(w, "ACTION_REQUIRED", e.actor(), origin(e), e);
			}
		}
		case "APPROVED" -> {
			w.setDetail("APPROVAL_RECEIVED_AWAITING_OFFER");
			move(w, "APPROVED", e.actor(), origin(e), e);
		}
		default -> {
			throw bad("INVALID_DECISION");
		}
		}
	}

	private void offerCreated(WorkflowRow w, JsonNode d, Event e) {
		SubmissionRow submission = matching(w, d);
		lenderReady(w);
		UUID id = uuid(d, "offerId");
		OfferRow existing = repository.find(OfferRow.class, id);
		if (existing != null) {
			childBinding(w, existing.getApplicationId(), existing.getSubmissionId());
			return;
		}
		if (repository.countOffersForApplicationQuery().setParameter("a", w.getId()).getSingleResult() > 0) {
			throw conflict("SECOND_OFFER_UNSUPPORTED");
		}
		OfferRow offer = new OfferRow();
		offer.setId(id);
		offer.setApplicationId(w.getId());
		offer.setSubmissionId(w.getSubmissionId());
		offer.setAmount(money(d, "offeredAmount"));
		offer.setFees(money(d, "feesAmount"));
		String rate = text(d, "annualInterestRatePercent");
		if (!rate.matches("[0-9]{1,3}(\\.[0-9]{1,4})?")) {
			throw bad("INVALID_INTEREST_RATE");
		}
		offer.setRate(new BigDecimal(rate).setScale(4, RoundingMode.UNNECESSARY));
		offer.setCurrency(oneOf(d, "currency", Set.of("INR")));
		offer.setInterestType(oneOf(d, "interestRateType", Set.of("FIXED")));
		offer.setFrequency(oneOf(d, "repaymentFrequency", Set.of("MONTHLY")));
		offer.setTenure(positive(d, "tenureMonths"));
		offer.setTermsReference(text(d, "termsReference"));
		offer.setExpiresAt(Instant.parse(text(d, "expiresAt")));
		if (offer.getAmount().signum() <= 0 || offer.getFees().signum() < 0 || offer.getRate().signum() < 0
				|| offer.getRate().compareTo(new BigDecimal("100")) > 0) {
			throw bad("INVALID_OFFER_TERMS");
		}
		offer.setStatus("ACTIVE");
		offer.setAcknowledgement("PENDING");
		repository.save(offer);
		// A complete authoritative lender offer is itself an approval, even
		// when the officer used the standalone offer endpoint.
		submission.setStatus("APPROVED");
		// An offer supersedes the lender's outstanding information requests.
		// Waiting for a later answer acknowledgement would block its own sequence.
		for (InformationRow question : repository.openInformationRequestsQuery().setParameter("a", w.getId())
				.getResultList()) {
			childBinding(w, question.getApplicationId(), question.getSubmissionId());
			question.setStatus("CANCELLED");
			question.incrementVersion();
		}
		boolean alreadyApproved = "APPROVED".equals(w.getStatus());
		w.setDetail(null);
		if (alreadyApproved)
			changed(w, e.actor(), origin(e), e);
		else
			move(w, "APPROVED", e.actor(), origin(e), e);
		expire(w, offer, origin(e), e);
	}

	private void offerWithdrawn(WorkflowRow w, JsonNode d, Event e) {
		matching(w, d);
		OfferRow offer = repository.find(OfferRow.class, uuid(d, "offerId"));
		if (offer == null) {
			throw new Platform.Pending("OFFER_NOT_RECEIVED");
		}
		childBinding(w, offer.getApplicationId(), offer.getSubmissionId());
		if (offer.getStatus().equals("ACTIVE")) {
			offer.setStatus("WITHDRAWN");
			offer.incrementVersion();
			move(w, "OFFER_EXPIRED", e.actor(), origin(e), e);
		}
	}

	private void offerAck(WorkflowRow w, JsonNode d, Event e) {
		matching(w, d);
		OfferRow offer = repository.find(OfferRow.class, uuid(d, "offerId"));
		if (offer == null) {
			throw new Platform.Pending("ACCEPTANCE_NOT_COMMITTED");
		}
		childBinding(w, offer.getApplicationId(), offer.getSubmissionId());
		if (!offer.getStatus().equals("ACCEPTED")) {
			throw new Platform.Pending("ACCEPTANCE_NOT_COMMITTED");
		}
		if (!Objects.equals(offer.getAcceptanceId(), uuid(d, "acceptanceId"))) {
			throw bad("ACCEPTANCE_ID_MISMATCH");
		}
		String acknowledgement = oneOf(d, "lenderAcknowledgementStatus", Set.of("ACKNOWLEDGED", "FAILED"));
		if (offer.getAcknowledgement().equals("ACKNOWLEDGED")) {
			return;
		}
		offer.setAcknowledgement(acknowledgement);
		if (acknowledgement.equals("ACKNOWLEDGED")) {
			resolveOperation(w, "ACCEPT_OFFER", d);
		} else {
			technical(w, "task:" + uuid(d, "taskId"), "ACCEPT_OFFER", d, false, "MANUAL_INTERVENTION_REQUIRED",
					"LENDER_ACCEPTANCE_FAILED");
		}
		changed(w, e.actor(), origin(e), e);
	}

	private void cancellationAck(WorkflowRow w, JsonNode d, Event e) {
		String acknowledgement = oneOf(d, "cancellationAcknowledgementStatus", Set.of("ACKNOWLEDGED", "FAILED"));
		SubmissionRow submission = binding(w, d);
		if (d.hasNonNull("cancellationId") && !Objects.equals(w.getCancellationId(), uuid(d, "cancellationId"))) {
			throw bad("CANCELLATION_ID_MISMATCH");
		}
		// Confirmed cancellation is final. A delayed failure must not undo it,
		// including reference cancellations that never had an external ID.
		if ("ACKNOWLEDGED".equals(submission.getCancellationAcknowledgement())) {
			if (submission.getExternalId() != null)
				matching(w, d);
			return;
		}
		if (!d.path("cancellationByReference").asBoolean() || !acknowledgement.equals("ACKNOWLEDGED")) {
			matching(w, d);
		}
		submission.setCancellationAcknowledgement(acknowledgement);
		if (acknowledgement.equals("ACKNOWLEDGED")) {
			resolveOperation(w, "CANCEL", d);
		} else {
			technical(w, "task:" + uuid(d, "taskId"), "CANCEL", d, false, "MANUAL_INTERVENTION_REQUIRED",
					"LENDER_CANCELLATION_FAILED");
		}
		changed(w, e.actor(), origin(e), e);
	}

	public JsonNode workflow(UUID id, Jwt jwt) {
		WorkflowRow w = required(WorkflowRow.class, id);
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), w.getMarketplaceId(), true);
		return view(w);
	}

	public JsonNode offers(UUID id, int page, int size, Jwt jwt) {
		validatePage(page, size);
		WorkflowRow w = locked(id);
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), null, true);
		List<OfferRow> rows = repository.offersForApplicationQuery().setParameter("a", id).setFirstResult(page * size)
				.setMaxResults(size).getResultList();
		for (OfferRow offer : rows) {
			expire(w, offer, p.actor(jwt), null);
		}
		long total = repository.countOffersForApplicationPageQuery().setParameter("a", id).getSingleResult();
		return p.json(data("items", rows.stream().map(this::offerView).toList(), "page", page, "size", size,
				"totalElements", total));
	}

	private void informationAccess(WorkflowRow w, Jwt jwt, boolean readOnly) {
		if (p.hasRole(jwt, "MARKETPLACE")) {
			Platform.check(jwt.getClaimAsString("client_id") == null
					&& w.getMarketplaceId().toString().equals(p.marketplaceId(jwt)), 404, "RESOURCE_NOT_FOUND");
			return;
		}
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), readOnly ? w.getLenderId() : null, null, readOnly);
	}

	public JsonNode questions(UUID id, int page, int size, Jwt jwt) {
		validatePage(page, size);
		WorkflowRow w = required(WorkflowRow.class, id);
		informationAccess(w, jwt, true);
		List<JsonNode> items = repository.informationRequestsForApplicationQuery().setParameter("a", id)
				.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
				.map(question -> p.json(data("informationRequestId", question.getId(), "submissionId",
						question.getSubmissionId(), "status", question.getStatus(), "createdAt",
						question.getCreatedAt(), "answeredAt", question.getAnsweredAt(), "version",
						question.getVersion(), "questions", parse(question.getQuestionsJson()), "answers",
						question.getAnswersJson() == null ? null : parse(question.getAnswersJson()),
						"syntheticDocumentReferences",
						question.getDocumentsJson() == null ? null : parse(question.getDocumentsJson()),
						"lenderAcknowledgementStatus", question.getAcknowledgement())))
				.toList();
		long total = repository.countInformationRequestsForApplicationQuery().setParameter("a", id).getSingleResult();
		return p.json(data("items", items, "page", page, "size", size, "totalElements", total));
	}

	public JsonNode submissions(int page, int size, String lender, Jwt jwt) {
		validatePage(page, size);
		if (p.hasRole(jwt, "LENDER_OFFICER")) {
			String own = p.lenderId(jwt);
			if (own == null) {
				throw new ResponseStatusException(FORBIDDEN);
			}
			if (lender != null && !own.equals(lender)) {
				throw new ResponseStatusException(NOT_FOUND);
			}
			lender = own;
		} else if (!p.hasRole(jwt, "ADMIN") && !p.hasRole(jwt, "MARKETPLACE_OPERATIONS")
				&& !p.hasRole(jwt, "MARKETPLACE")) {
			throw new ResponseStatusException(FORBIDDEN);
		}
		UUID marketplace = null;
		if (!p.hasRole(jwt, "ADMIN") && !p.hasRole(jwt, "LENDER_OFFICER")) {
			try {
				marketplace = UUID.fromString(p.marketplaceId(jwt));
			} catch (RuntimeException invalidClaim) {
				throw new ResponseStatusException(FORBIDDEN);
			}
		}
		var query = repository.submissionsQuery(lender, marketplace);
		var count = repository.countSubmissionsQuery(lender, marketplace);
		if (lender != null) {
			query.setParameter("l", lender);
			count.setParameter("l", lender);
		}
		if (marketplace != null) {
			query.setParameter("m", marketplace);
			count.setParameter("m", marketplace);
		}
		List<Map<String, Object>> items = query.setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size)
				.getResultList().stream()
				.map(submission -> data("submissionId", submission.getId(), "applicationId",
						submission.getApplicationId(), "applicationRevision", submission.getRevision(), "lenderId",
						submission.getLenderId(), "productId", submission.getProductId(), "status",
						submission.getStatus(), "lenderApplicationId", submission.getExternalId(), "version",
						submission.getRowVersion() == null ? 0L : submission.getRowVersion(),
						"cancellationAcknowledgementStatus", submission.getCancellationAcknowledgement()))
				.toList();
		return p.json(data("items", items, "page", page, "size", size, "totalElements", count.getSingleResult()));
	}

	private JsonNode lenderSummary(WorkflowRow w) {
		if (w.getEligibilityJson() == null)
			return null;
		var all = parse(w.getEligibilityJson());
		ObjectNode summary = p.json(Map.of()).deepCopy();
		for (String key : List.of("creditScoreBand", "monthlySurplus", "averageMonthlyNetSales", "averageMonthlyPayout",
				"returnRatePercent", "calculationVersion", "evaluatedAt"))
			if (all.hasNonNull(key))
				summary.set(key, all.get(key));
		for (JsonNode result : all.path("lenderResults"))
			if (Objects.equals(w.getLenderId(), result.path("lenderId").asText())) {
				summary.set("lenderAssessment", result);
				break;
			}
		return summary;
	}

	public JsonNode submission(UUID id, Jwt jwt) {
		SubmissionRow submission = required(SubmissionRow.class, id);
		WorkflowRow w = required(WorkflowRow.class, submission.getApplicationId());
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), submission.getLenderId(), null, true);
		return p.json(data("submissionId", submission.getId(), "applicationId", w.getId(), "lenderId",
				submission.getLenderId(), "productId", submission.getProductId(), "applicationRevision",
				submission.getRevision(), "registeredBusinessName", w.getLegalName(), "businessProfile",
				parse(w.getProfileJson()), "consentStatus", w.isConsentGranted() ? "GRANTED" : "REQUIRED", "kycStatus",
				parse(w.getProfileJson()).path("phase0").isObject() ? "VERIFIED" : "LEGACY_RECORD",
				"eligibilitySummary", lenderSummary(w), "requestedAmount",
				w.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency", w.getCurrency(),
				"loanPurpose", w.getPurpose(), "financialSnapshotId", w.getSnapshotId(), "eligibilityResultId",
				w.getEligibilityId(), "status", submission.getStatus(), "lenderApplicationId",
				submission.getExternalId(), "version",
				submission.getRowVersion() == null ? 0L : submission.getRowVersion(), "workflow", view(w)));
	}

	public WorkflowReply offerAction(UUID id, OfferActionRequest request, String key, Jwt jwt, boolean accept) {
		OfferRow found = required(OfferRow.class, id);
		WorkflowRow w = locked(found.getApplicationId());
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), null, w.getMarketplaceId(), false);
		OfferRow offer = repository.find(OfferRow.class, id, LockModeType.PESSIMISTIC_WRITE);
		Actor actor = p.actor(jwt);
		String operationName = accept ? "accept" : "decline";
		String idempotencyId = idempotency(jwt, operationName + ":" + id, key);
		WorkflowReply replay = replay(idempotencyId, request);
		if (replay != null) {
			return replay;
		}
		WorkflowReply reply;
		if (expire(w, offer, actor, null)) {
			reply = failure("OFFER_EXPIRED");
		} else if (offer.getVersion() != request.expectedOfferVersion() || !offer.getStatus().equals("ACTIVE")
				|| !w.getStatus().equals("APPROVED") || !sharingAllowed(w)) {
			reply = failure("OFFER_STATE_OR_VERSION_CONFLICT");
		} else {
			offer.setStatus(accept ? "ACCEPTED" : "DECLINED");
			offer.incrementVersion();
			offer.setAcceptedAt(accept ? Instant.now() : null);
			UUID operation = UUID.randomUUID();
			offer.setAcceptanceId(accept ? operation : null);
			move(w, accept ? "OFFER_ACCEPTED" : "OFFER_DECLINED", actor, actor, null);
			p.emit(accept ? "loan.offer.accepted" : "loan.offer.declined", w.getId(), w.getWorkflowVersion(),
					data("offerId", id, "submissionId", offer.getSubmissionId(), accept ? "acceptanceId" : "declineId",
							operation, "acceptedAt", offer.getAcceptedAt(), "applicationRevision", w.getRevision(),
							"lenderId", w.getLenderId(), "operationAt", Instant.now(), "expectedOfferVersion",
							request.expectedOfferVersion(), "lenderAcknowledgementStatus", offer.getAcknowledgement()),
					actor, actor, null);
			reply = new WorkflowReply(200, offerView(offer));
		}
		remember(idempotencyId, request, reply);
		return reply;
	}

	public WorkflowReply answer(UUID id, InformationAnswersRequest request, String key, Jwt jwt) {
		InformationRow found = required(InformationRow.class, id);
		WorkflowRow w = locked(found.getApplicationId());
		informationAccess(w, jwt, false);
		InformationRow question = repository.find(InformationRow.class, id, LockModeType.PESSIMISTIC_WRITE);
		String idempotencyId = idempotency(jwt, "answers:" + id, key);
		WorkflowReply replay = replay(idempotencyId, request);
		if (replay != null) {
			return replay;
		}
		if (!w.getStatus().equals("ACTION_REQUIRED") || !sharingAllowed(w) || !question.getStatus().equals("OPEN")
				|| question.getAnswersJson() != null || question.getVersion() != request.expectedInformationVersion()) {
			throw conflict("INFORMATION_STATE_CONFLICT");
		}
		Set<String> expected = new HashSet<>();
		for (JsonNode item : parse(question.getQuestionsJson())) {
			expected.add(text(item, "questionId"));
		}
		Set<String> actual = new HashSet<>();
		for (InformationAnswer answer : request.answers()) {
			if (!actual.add(answer.questionId())) {
				throw bad("DUPLICATE_ANSWER");
			}
		}
		if (!expected.equals(actual)) {
			throw bad("ANSWER_QUESTION_MISMATCH");
		}
		for (String reference : request.syntheticDocumentReferences()) {
			if (!reference.matches("synthetic://[A-Za-z0-9_./-]{1,200}")) {
				throw bad("SYNTHETIC_REFERENCE_REQUIRED");
			}
		}
		Actor actor = p.actor(jwt);
		question.setAnswersJson(p.write(request.answers()));
		question.setAnsweredAt(Instant.now());
		question.setDocumentsJson(p.write(request.syntheticDocumentReferences()));
		question.setAcknowledgement("PENDING");
		question.incrementVersion();
		UUID operation = UUID.randomUUID();
		p.job(operation, "integration",
				"/api/v1/internal/submissions/" + question.getSubmissionId() + "/information-responses",
				data("informationRequestId", question.getId(), "answers", request.answers(),
						"syntheticDocumentReferences", request.syntheticDocumentReferences(), "originActor", actor),
				operation.toString(), w.getId(), actor);
		p.emit("lender.information.response.recorded", w.getId(), w.getWorkflowVersion(),
				data("informationRequestId", question.getId(), "submissionId", question.getSubmissionId()), actor,
				actor, null);
		WorkflowReply reply = new WorkflowReply(202, p.json(data("operationId", operation, "informationRequestId",
				question.getId(), "version", question.getVersion(), "lenderAcknowledgementStatus", "PENDING")));
		remember(idempotencyId, request, reply);
		return reply;
	}

	public WorkflowReply command(UUID id, WorkflowVersionRequest request, String key, Jwt jwt, boolean refresh) {
		WorkflowRow w = locked(id);
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), null, w.getMarketplaceId(), false);
		String idempotencyId = idempotency(jwt, (refresh ? "refresh:" : "cancel:") + id, key);
		WorkflowReply replay = replay(idempotencyId, request);
		if (replay != null) {
			return replay;
		}
		if (w.getWorkflowVersion() != request.expectedWorkflowVersion() || TERMINAL.contains(w.getStatus())) {
			throw conflict("WORKFLOW_VERSION_CONFLICT");
		}
		Actor actor = p.actor(jwt);
		if (refresh) {
			boolean permissionIssue = Set.of("FINANCIAL_CONSENT_REQUIRED", "FINANCIAL_CONSENT_EXPIRED")
					.contains(Objects.toString(w.getDetail(), ""));
			if (!w.getStatus().equals("ACTION_REQUIRED") || w.isDispatchAuthorized() || w.isRevisionPending()
					|| !w.isConsentGranted() || w.getDetail() == null
					|| !Set.of("FINANCIAL_DATA_INCOMPLETE", "FINANCIAL_DATA_INCONSISTENT",
							"FINANCIAL_DATA_INSUFFICIENT", "FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED",
							"FINANCIAL_CONSENT_REQUIRED", "FINANCIAL_CONSENT_EXPIRED", "CREDIT_INFORMATION_REQUIRED",
							"ELIGIBILITY_PROCESSING_FAILED").contains(w.getDetail())) {
				throw conflict("REFRESH_NOT_ALLOWED");
			}
			if (!permissionIssue)
				w.setLastRefreshRevision(w.getRevision());
			startVerification(w, actor, null);
		} else {
			cancel(w, actor, null, "SELLER_CANCELLED");
		}
		UUID operationId = refresh ? w.getVerificationId()
				: w.getCancellationId() == null ? UUID.randomUUID() : w.getCancellationId();
		WorkflowReply result = new WorkflowReply(202, p.json(data("operationId", operationId, "workflow", view(w))));
		remember(idempotencyId, request, result);
		return result;
	}

	private void cancel(WorkflowRow w, Actor origin, Event cause, String reason) {
		w.setDispatchAuthorized(false);
		w.setDetail(reason);
		if (!TERMINAL.contains(w.getStatus())) {
			move(w, "CANCELLED", p.systemActor(), origin, cause);
			for (OfferRow offer : repository.activeOffersForApplicationQuery().setParameter("a", w.getId())
					.getResultList()) {
				offer.setStatus("WITHDRAWN");
				offer.incrementVersion();
			}
			repository.cancelOpenInformationRequestsQuery().setParameter("a", w.getId()).executeUpdate();
		}
		if (w.getSubmissionId() != null && w.getCancellationId() == null && !w.getStatus().equals("OFFER_ACCEPTED")) {
			w.setCancellationId(UUID.randomUUID());
			p.job(w.getCancellationId(), "integration",
					"/api/v1/internal/submissions/" + w.getSubmissionId() + "/cancellation",
					data("cancellationId", w.getCancellationId(), "applicationId", w.getId(), "submissionId",
							w.getSubmissionId(), "applicationRevision", w.getRevision(), "lenderId", w.getLenderId(),
							"reason", reason, "originActor", origin),
					w.getCancellationId().toString(), w.getId(), origin);
		}
	}

	public JsonNode correction(UUID id, RevisionGuardRequest request, Jwt jwt, boolean abort) {
		p.machine(jwt, "workflow.revision", applicationClient);
		WorkflowRow w = locked(id);
		if (abort) {
			if (w.isRevisionPending() && Objects.equals(w.getRevisionOperation(), request.operationId())) {
				w.setRevisionPending(false);
				w.setRevisionOperation(null);
				changed(w, p.systemActor(), request.originActor(), null);
			}
			return p.json(data("authorized", false, "operationId", request.operationId()));
		}
		if (w.isRevisionPending() && Objects.equals(w.getRevisionOperation(), request.operationId())
				&& w.getExpectedRevision() == request.newRevision()) {
			return p.json(data("authorized", true, "operationId", request.operationId()));
		}
		if (!w.getStatus().equals("ACTION_REQUIRED") || w.getSubmissionId() != null || w.isDispatchAuthorized()
				|| w.isRevisionPending() || w.getRevision() != request.expectedRevision()
				|| request.newRevision() != w.getRevision() + 1) {
			throw conflict("CORRECTION_NOT_ALLOWED");
		}
		w.setRevisionPending(true);
		w.setRevisionOperation(request.operationId());
		w.setExpectedRevision(request.newRevision());
		changed(w, p.systemActor(), request.originActor(), null);
		return p.json(data("authorized", true, "operationId", request.operationId()));
	}

	private void revised(WorkflowRow w, JsonNode d, Actor origin, Event cause) {
		if (!w.isRevisionPending() || !Objects.equals(w.getRevisionOperation(), uuid(d, "operationId"))
				|| w.getExpectedRevision() != positive(d, "applicationRevision")) {
			return;
		}
		// Consume a correction authorised before cancellation without reopening a
		// terminal workflow.
		if (TERMINAL.contains(w.getStatus())) {
			w.setRevision(w.getExpectedRevision());
			w.setRevisionPending(false);
			w.setRevisionOperation(null);
			changed(w, p.systemActor(), origin, cause);
			return;
		}
		w.setRevision(w.getExpectedRevision());
		w.setRevisionPending(false);
		w.setRevisionOperation(null);
		inputs(w, d);
		if (d.has("sellerId"))
			w.setSellerId(optionalText(d, "sellerId", 120));
		if (d.has("sellerSubject"))
			w.setSellerSubject(optionalText(d, "sellerSubject", 255));
		if (d.has("marketplaceSellerId"))
			w.setMarketplaceSellerId(optionalText(d, "marketplaceSellerId", 120));
		if (w.isConsentGranted()) {
			startVerification(w, origin, cause);
		}
	}

	public JsonNode consentOperation(UUID id, JsonNode body, Jwt jwt) {
		p.machine(jwt, "workflow.consent", consentClient);
		WorkflowRow w = locked(id);
		UUID operation = uuid(body, "operationId");
		String idempotencyId = p.hash(List.of("consent", w.getId(), operation));
		WorkflowReply replay = replay(idempotencyId, body);
		if (replay != null) {
			return replay.body();
		}
		Actor immediate = new Actor("SYSTEM_CLIENT", jwt.getSubject(), null);
		Actor origin = body.hasNonNull("originActor") ? p.read(body.path("originActor"), Actor.class) : immediate;
		consent(w, body, origin, null);
		JsonNode result = p.json(data("operationId", operation, "status", "APPLIED", "workflow", view(w)));
		p.emit("consent.applied", id, w.getWorkflowVersion(),
				data("operationId", operation, "consentId", w.getConsentId(), "consentVersion", w.getConsentVersion()),
				immediate, origin, null);
		remember(idempotencyId, body, new WorkflowReply(200, result));
		return result;
	}

	public void expireOffers() {
		List<UUID> ids = repository.expiredOfferIdsQuery().setParameter("now", Instant.now()).setMaxResults(100)
				.getResultList();
		for (UUID id : ids) {
			p.tx(() -> {
				OfferRow found = repository.find(OfferRow.class, id);
				if (found == null) {
					return null;
				}
				WorkflowRow w = locked(found.getApplicationId());
				OfferRow offer = repository.find(OfferRow.class, id, LockModeType.PESSIMISTIC_WRITE);
				expire(w, offer, p.systemActor(), null);
				return null;
			});
		}
	}

	public record PendingEligibility(UUID applicationId, UUID operationId, int applicationRevision) {
	}

	@Transactional(readOnly = true)
	public List<PendingEligibility> pendingEligibility() {
		return repository.pendingEligibilityQuery().setMaxResults(20).getResultList().stream()
				.map(w -> new PendingEligibility(w.getId(), w.getEligibilityOperationId(), w.getRevision())).toList();
	}

	public void eligibilityProcessingFailed(PendingEligibility expected) {
		WorkflowRow w = locked(expected.applicationId());
		if (!"ELIGIBILITY_CHECKING".equals(w.getStatus()) || w.isRevisionPending()
				|| w.getRevision() != expected.applicationRevision()
				|| !Objects.equals(w.getEligibilityOperationId(), expected.operationId())
				|| w.getSubmissionId() != null)
			return;
		w.setDetail("ELIGIBILITY_PROCESSING_FAILED");
		move(w, "ACTION_REQUIRED", p.systemActor(), p.systemActor(), null);
	}

	private boolean expire(WorkflowRow w, OfferRow offer, Actor origin, Event cause) {
		if (!offer.getStatus().equals("ACTIVE") || offer.getExpiresAt().isAfter(Instant.now())) {
			return false;
		}
		if (TERMINAL.contains(w.getStatus())) {
			return false;
		}
		offer.setStatus("EXPIRED");
		offer.incrementVersion();
		move(w, "OFFER_EXPIRED", p.systemActor(), origin, cause);
		p.emit("loan.offer.expired", w.getId(), w.getWorkflowVersion(),
				data("offerId", offer.getId(), "submissionId", offer.getSubmissionId()), p.systemActor(), origin,
				cause);
		return true;
	}

	private void move(WorkflowRow w, String next, Actor actor, Actor origin, Event cause) {
		if (w.getStatus().equals(next)) {
			return;
		}
		if (TERMINAL.contains(w.getStatus()) || (!next.equals("CANCELLED") && !next.equals("ACTION_REQUIRED")
				&& !NEXT.getOrDefault(w.getStatus(), Set.of()).contains(next))) {
			throw conflict("INVALID_WORKFLOW_TRANSITION");
		}
		String previous = w.getStatus();
		w.setStatus(next);
		changed(w, actor, origin, cause, previous);
	}

	private void changed(WorkflowRow w, Actor actor, Actor origin, Event cause) {
		changed(w, actor, origin, cause, w.getStatus());
	}

	private void changed(WorkflowRow w, Actor actor, Actor origin, Event cause, String previousStatus) {
		w.incrementWorkflowVersion();
		w.setUpdatedAt(Instant.now());
		p.emit("application.status.changed", w.getId(), w.getWorkflowVersion(),
				data("previousStatus", previousStatus, "status", w.getStatus(), "processingHealth", w.getHealth(),
						"workflowVersion", w.getWorkflowVersion(), "applicationRevision", w.getRevision(), "reasonCode",
						w.getDetail()),
				actor, origin, cause);
	}

	private String displayReason(WorkflowRow w) {
		if ("REJECTED".equals(w.getStatus()) && w.getDecisionJson() != null) {
			String code = parse(w.getDecisionJson()).path("reasonCode").asText("");
			if (code.matches("[A-Z][A-Z0-9_]{0,99}"))
				return code;
		}
		return w.getDetail();
	}

	private JsonNode view(WorkflowRow w) {
		return p.json(data("applicationId", w.getId(), "status", w.getStatus(), "processingHealth", w.getHealth(),
				"processingIssues", activeIssues(w), "workflowVersion", w.getWorkflowVersion(), "applicationRevision",
				w.getRevision(), "revisionPending", w.isRevisionPending(), "lenderId", w.getLenderId(), "productId",
				w.getProductId(), "submissionId", w.getSubmissionId(), "verificationId", w.getVerificationId(),
				"evaluationRequestId", w.getEvaluationId(), "asOfDate", w.getEvaluationAsOfDate(),
				"eligibilityOperationId", w.getEligibilityOperationId(), "eligibilityStatusUrl",
				w.getEligibilityStatusUrl(), "financialSnapshotId", w.getSnapshotId(), "eligibilityResultId",
				w.getEligibilityId(), "cancellationId", w.getCancellationId(), "reasonCode", displayReason(w)));
	}

	private JsonNode offerView(OfferRow offer) {
		return p.json(data("offerId", offer.getId(), "submissionId", offer.getSubmissionId(), "offeredAmount",
				offer.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency",
				offer.getCurrency(), "annualInterestRatePercent", offer.getRate().toPlainString(), "interestRateType",
				offer.getInterestType(), "tenureMonths", offer.getTenure(), "repaymentFrequency", offer.getFrequency(),
				"feesAmount", offer.getFees().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "termsReference",
				offer.getTermsReference(), "expiresAt", offer.getExpiresAt(), "status", offer.getStatus(), "version",
				offer.getVersion(), "acceptanceId", offer.getAcceptanceId(), "acceptedAt", offer.getAcceptedAt(),
				"lenderAcknowledgementStatus", offer.getAcknowledgement()));
	}

	private static void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
			throw bad("INVALID_PAGE");
		}
	}

	private List<JsonNode> activeIssues(WorkflowRow w) {
		List<JsonNode> result = new ArrayList<>();
		issues(w).fields().forEachRemaining(entry -> {
			JsonNode value = entry.getValue();
			if (value.path("resolved").asBoolean())
				return;
			String issueId = entry.getKey();
			int separator = issueId.indexOf(':');
			String prefix = separator < 0 ? "task" : issueId.substring(0, separator);
			String operationId = separator < 0 ? issueId : issueId.substring(separator + 1);
			ObjectNode issue = ((ObjectNode) value).deepCopy();
			issue.put("issueId", issueId);
			issue.put("operationId", operationId);
			issue.put("kind",
					prefix.equals("job") ? "JOB"
							: prefix.equals("callback") || value.path("operation").asText().equals("CALLBACK")
									? "CALLBACK"
									: "TASK");
			result.add(issue);
		});
		return result;
	}

	private WorkflowRow locked(UUID id) {
		WorkflowRow w = repository.find(WorkflowRow.class, id, LockModeType.PESSIMISTIC_WRITE);
		if (w == null) {
			throw new ResponseStatusException(NOT_FOUND);
		}
		return w;
	}

	private <T> T required(Class<T> type, UUID id) {
		T row = repository.find(type, id);
		if (row == null) {
			throw new ResponseStatusException(NOT_FOUND);
		}
		return row;
	}

	private Actor origin(Event e) {
		return e.originActor() != null ? e.originActor() : e.actor();
	}

	private JsonNode parse(String value) {
		return value == null ? p.json(null) : p.read(value, JsonNode.class);
	}

	private static JsonNode optionalNode(JsonNode object, String field) {
		return object.hasNonNull(field) ? object.get(field) : null;
	}

	private String idempotency(Jwt jwt, String operation, String key) {
		if (key == null || key.isBlank() || key.length() > 128) {
			throw bad("IDEMPOTENCY_KEY_REQUIRED");
		}
		return p.hash(List.of(jwt.getSubject(), operation, key));
	}

	private WorkflowReply replay(String id, Object request) {
		IdempotencyRow row = repository.find(IdempotencyRow.class, id);
		if (row == null) {
			return null;
		}
		if (!row.getRequestHash().equals(p.hash(request))) {
			throw conflict("IDEMPOTENCY_KEY_CONFLICT");
		}
		return new WorkflowReply(row.getHttpStatus(), parse(row.getResponseJson()));
	}

	private void remember(String id, Object request, WorkflowReply reply) {
		IdempotencyRow row = new IdempotencyRow();
		row.setId(id);
		row.setRequestHash(p.hash(request));
		row.setResponseJson(p.write(reply.body()));
		row.setHttpStatus(reply.status());
		repository.save(row);
	}

	private WorkflowReply failure(String code) {
		String correlation = org.slf4j.MDC.get("correlationId");
		if (correlation == null || correlation.isBlank()) {
			correlation = UUID.randomUUID().toString();
		}
		return new WorkflowReply(409,
				p.json(data("code", code, "message", code, "correlationId", correlation, "fieldErrors", List.of())));
	}

	private static Map<String, Object> data(Object... entries) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index < entries.length; index += 2) {
			result.put((String) entries[index], entries[index + 1]);
		}
		return result;
	}

	private static String text(JsonNode data, String field) {
		JsonNode node = data.path(field);
		if (!node.isTextual() || node.asText().isBlank()) {
			throw bad("MISSING_" + field);
		}
		return node.asText();
	}

	private static UUID uuid(JsonNode data, String field) {
		try {
			return UUID.fromString(text(data, field));
		} catch (IllegalArgumentException exception) {
			throw bad("INVALID_" + field);
		}
	}

	private static String optionalText(JsonNode data, String field, int maxLength) {
		if (!data.hasNonNull(field))
			return null;
		String value = text(data, field);
		if (value.codePointCount(0, value.length()) > maxLength)
			throw bad("INVALID_" + field);
		return value;
	}

	private static LocalDate date(JsonNode data, String field) {
		try {
			String value = text(data, field);
			if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
				throw bad("INVALID_" + field);
			return LocalDate.parse(value);
		} catch (java.time.format.DateTimeParseException invalid) {
			throw bad("INVALID_" + field);
		}
	}

	private static int positive(JsonNode data, String field) {
		JsonNode node = data.path(field);
		if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 1) {
			throw bad("INVALID_" + field);
		}
		return node.asInt();
	}

	private static Instant timestamp(JsonNode data, String field) {
		try {
			return Instant.parse(text(data, field));
		} catch (java.time.format.DateTimeParseException invalid) {
			throw bad("INVALID_" + field);
		}
	}

	private static void stringArray(JsonNode data, String field) {
		if (!data.path(field).isArray())
			throw bad("INVALID_" + field);
		for (JsonNode value : data.path(field)) {
			if (!value.isTextual() || value.asText().isBlank())
				throw bad("INVALID_" + field);
		}
	}

	private static BigDecimal money(JsonNode data, String field) {
		try {
			String input = text(data, field);
			if (!input.matches("[0-9]{1,17}\\.[0-9]{2}")) {
				throw bad("INVALID_" + field);
			}
			BigDecimal value = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
			if (value.precision() > 19) {
				throw bad("INVALID_" + field);
			}
			return value;
		} catch (ArithmeticException | NumberFormatException exception) {
			throw bad("INVALID_" + field);
		}
	}

	private static String oneOf(JsonNode data, String field, Set<String> choices) {
		String value = text(data, field);
		if (!choices.contains(value)) {
			throw bad("INVALID_" + field);
		}
		return value;
	}

	private static boolean contains(JsonNode array, String value) {
		if (value == null || !array.isArray()) {
			return false;
		}
		for (JsonNode item : array) {
			if (item.isTextual() && value.equals(item.asText())) {
				return true;
			}
		}
		return false;
	}

	private static ResponseStatusException bad(String code) {
		return new ResponseStatusException(BAD_REQUEST, code);
	}

	private static ResponseStatusException conflict(String code) {
		return new ResponseStatusException(CONFLICT, code);
	}
}
