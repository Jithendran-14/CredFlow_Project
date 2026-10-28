package com.credflow.consent.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import com.credflow.consent.client.IdentityProviderClient;
import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;

import jakarta.validation.Validator;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "credflow.identity-processing.enabled", havingValue = "true")
public class IdentityProcessingWorker {

	private static final Logger log = LoggerFactory.getLogger(IdentityProcessingWorker.class);

	private final IdentityProcessingStore store;
	private final VerificationPrecheckService prechecks;
	private final SellerIdentityMappingService mappings;
	private final IdentityProviderClient provider;
	private final ObjectMapper objectMapper;
	private final Validator validator;

	public IdentityProcessingWorker(IdentityProcessingStore store, VerificationPrecheckService prechecks,
			SellerIdentityMappingService mappings, IdentityProviderClient provider, ObjectMapper objectMapper,
			Validator validator) {

		this.store = store;
		this.prechecks = prechecks;
		this.mappings = mappings;
		this.provider = provider;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	@Scheduled(fixedDelayString = "${credflow.identity-processing.fixed-delay-ms:5000}")
	public void processNext() {

		try {
			var candidate = store.claimNext();

			if (candidate.isEmpty()) {
				return;
			}

			process(candidate.get());

		} catch (Exception exception) {
			log.error("Identity processing poll failed: errorType={}", exception.getClass().getSimpleName());
		}
	}

	private void process(IdentityProcessingStore.Claim claim) {

		try {
			VerificationRequest request = objectMapper.readValue(claim.verificationPayload(),
					VerificationRequest.class);

			if (request == null || !validator.validate(request).isEmpty()
					|| !Objects.equals(request.verificationId(), claim.input().verificationRequestId())
					|| !Objects.equals(request.applicationId(), claim.input().applicationId())
					|| !Objects.equals(request.applicationRevision(),
							Integer.valueOf(claim.input().applicationRevision()))) {

				store.recordFailure(claim, "IDENTITY_JOB_INPUT_MISMATCH", true);
				return;
			}

			// READY is a previous checkpoint, so check current consent again.
			var result = prechecks.check(request);

			if (result.outcome() == VerificationPrecheckResult.Outcome.RETRY_LATER) {

				store.recordFailure(claim, result.reasonCode(), false);
				return;
			}

			if (result.outcome() == VerificationPrecheckResult.Outcome.REJECTED) {

				store.recordFailure(claim, result.reasonCode(), true);
				return;
			}

			var mapping = mappings.findActive(result.sellerId(), result.marketplaceId());

			if (mapping.isEmpty() || !claim.input().fixtureIdentityId().equals(mapping.get().fixtureIdentityId())) {

				store.recordFailure(claim, "IDENTITY_MAPPING_CHANGED_OR_DISABLED", true);
				return;
			}

			var response = provider.check(claim.input(), claim.correlationId());

			boolean saved = store.recordReceived(claim, response, objectMapper.writeValueAsString(response));

			if (saved) {
				log.info("Identity provider response recorded: " + "verificationId={}, providerStatus={}",
						claim.input().verificationRequestId(), response.status());
			}

		} catch (RestClientResponseException exception) {
			store.recordFailure(claim, "IDENTITY_PROVIDER_HTTP_" + exception.getStatusCode().value(), false);

		} catch (Exception exception) {
			store.recordFailure(claim, "IDENTITY_PROCESSING_ERROR", false);

			log.warn("Identity processing failed: " + "verificationId={}, errorType={}",
					claim.input().verificationRequestId(), exception.getClass().getSimpleName());
		}
	}
}