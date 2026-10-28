package com.credflow.consent.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.credflow.consent.client.ApplicationOwnershipClient;
import com.credflow.consent.client.ApplicationOwnershipClient.ApplicationContext;
import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.entity.ConsentHistoryId;
import com.credflow.consent.entity.ConsentStatus;
import com.credflow.consent.repository.ConsentHistoryRepository;
import com.credflow.consent.repository.ConsentRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class VerificationPrecheckService {

	private static final Set<String> REQUIRED_SCOPES = Set.of("MARKETPLACE_DATA_READ", "IDENTITY_VERIFICATION",
			"LENDER_APPLICATION_SHARE");

	private final ApplicationOwnershipClient applications;
	private final ConsentRepository consents;
	private final ConsentHistoryRepository history;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate readTransaction;

	public VerificationPrecheckService(ApplicationOwnershipClient applications, ConsentRepository consents,
			ConsentHistoryRepository history, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {

		this.applications = applications;
		this.consents = consents;
		this.history = history;
		this.objectMapper = objectMapper;

		this.readTransaction = new TransactionTemplate(transactionManager);

		this.readTransaction.setReadOnly(true);

		this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public VerificationPrecheckResult check(VerificationRequest request) {

		ApplicationContext application;

		// The remote call runs before opening the local DB transaction.
		try {
			application = applications.getContext(request.applicationId());

		} catch (ResponseStatusException exception) {
			if (exception.getStatusCode().value() == 404) {
				return VerificationPrecheckResult.rejected("APPLICATION_NOT_FOUND");
			}

			return VerificationPrecheckResult.retry("APPLICATION_CONTEXT_UNAVAILABLE");
		}

		if (application.applicationRevision() == null || application.applicationRevision() < 1
				|| application.sellerId() == null || application.sellerId().isBlank()
				|| application.marketplaceId() == null) {

			return VerificationPrecheckResult.retry("INVALID_APPLICATION_CONTEXT");
		}

		if (!request.applicationRevision().equals(application.applicationRevision())) {

			return VerificationPrecheckResult.rejected("APPLICATION_REVISION_MISMATCH");
		}

		VerificationPrecheckResult result = readTransaction.execute(transaction -> checkConsent(request, application));

		if (result == null) {
			throw new IllegalStateException("VERIFICATION_PRECHECK_RETURNED_NO_RESULT");
		}

		return result;
	}

	private VerificationPrecheckResult checkConsent(VerificationRequest request, ApplicationContext application) {

		var consent = consents.findById(request.consentId().toString()).orElse(null);

		if (consent == null) {
			return VerificationPrecheckResult.rejected("CONSENT_NOT_FOUND");
		}

		if (!request.applicationId().toString().equals(consent.getApplicationId())) {

			return VerificationPrecheckResult.rejected("CONSENT_APPLICATION_MISMATCH");
		}

		if (!application.sellerId().equals(consent.getSellerId())) {

			return VerificationPrecheckResult.rejected("CONSENT_SELLER_MISMATCH");
		}

		if (!request.consentVersion().equals(consent.getConsentVersion())) {

			return VerificationPrecheckResult.rejected("CONSENT_VERSION_MISMATCH");
		}

		if (consent.getConsentStatus() != ConsentStatus.GRANTED) {
			return VerificationPrecheckResult.rejected("CONSENT_NOT_GRANTED");
		}

		var entry = history.findById(new ConsentHistoryId(request.consentId().toString(), request.consentVersion()))
				.orElse(null);

		if (entry == null) {
			return VerificationPrecheckResult.retry("CONSENT_HISTORY_MISSING");
		}

		if (entry.getConsentStatus() != ConsentStatus.GRANTED) {
			return VerificationPrecheckResult.retry("CONSENT_HISTORY_INCONSISTENT");
		}

		StoredConsent payload;

		try {
			payload = objectMapper.readValue(entry.getConsentPayload(), StoredConsent.class);

		} catch (RuntimeException exception) {
			return VerificationPrecheckResult.retry("CONSENT_PAYLOAD_INVALID");
		}

		if (payload == null || !request.consentId().equals(payload.consentId())
				|| !request.consentVersion().equals(payload.consentVersion()) || !"GRANTED".equals(payload.status())) {

			return VerificationPrecheckResult.retry("CONSENT_PAYLOAD_INCONSISTENT");
		}

		if (payload.scopes() == null || !payload.scopes().containsAll(REQUIRED_SCOPES)) {

			return VerificationPrecheckResult.rejected("REQUIRED_CONSENT_SCOPES_MISSING");
		}

		if (payload.sourceMarketplaceIds() == null
				|| !payload.sourceMarketplaceIds().contains(application.marketplaceId())) {

			return VerificationPrecheckResult.rejected("MARKETPLACE_DATA_PERMISSION_MISSING");
		}

		if (payload.recipientLenderIds() == null || payload.recipientLenderIds().isEmpty()) {

			return VerificationPrecheckResult.rejected("LENDER_SHARING_PERMISSION_MISSING");
		}

		if (!"APPLICATION_OPEN".equals(payload.validityPolicy()) || payload.purpose() == null
				|| payload.purpose().isBlank()) {

			return VerificationPrecheckResult.rejected("CONSENT_POLICY_INVALID");
		}

		return VerificationPrecheckResult.ready(application.sellerId(), application.marketplaceId());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StoredConsent(UUID consentId, Integer consentVersion, String status, Set<String> scopes,
			Set<UUID> sourceMarketplaceIds, Set<UUID> recipientLenderIds, String purpose, String validityPolicy) {
	}
}