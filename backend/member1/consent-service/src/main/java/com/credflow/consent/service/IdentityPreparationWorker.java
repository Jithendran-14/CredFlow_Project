package com.credflow.consent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;

import jakarta.validation.Validator;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "credflow.identity-preparation.enabled", havingValue = "true")
public class IdentityPreparationWorker {

	private static final Logger log = LoggerFactory.getLogger(IdentityPreparationWorker.class);

	private final IdentityProcessingStore store;
	private final VerificationPrecheckService prechecks;
	private final SellerIdentityMappingService mappings;
	private final ObjectMapper objectMapper;
	private final Validator validator;

	public IdentityPreparationWorker(IdentityProcessingStore store, VerificationPrecheckService prechecks,
			SellerIdentityMappingService mappings, ObjectMapper objectMapper, Validator validator) {

		this.store = store;
		this.prechecks = prechecks;
		this.mappings = mappings;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	@Scheduled(fixedDelayString = "${credflow.identity-preparation.fixed-delay-ms:5000}")
	public void prepare() {

		try {
			for (var candidate : store.findReadyRequests()) {
				prepareOne(candidate);
			}

		} catch (Exception exception) {
			log.error("Identity preparation poll failed: errorType={}", exception.getClass().getSimpleName());
		}
	}

	private void prepareOne(IdentityProcessingStore.Candidate candidate) {

		try {
			VerificationRequest request = objectMapper.readValue(candidate.payload(), VerificationRequest.class);

			if (request == null || !validator.validate(request).isEmpty()
					|| !candidate.verificationId().equals(request.verificationId().toString())) {

				throw new IllegalStateException("INVALID_STORED_VERIFICATION_REQUEST");
			}

			var result = prechecks.check(request);

			if (result.outcome() != VerificationPrecheckResult.Outcome.READY_FOR_IDENTITY_CHECK) {

				store.recordPreparationProblem(candidate, result);
				return;
			}

			var mapping = mappings.findActive(result.sellerId(), result.marketplaceId());

			if (mapping.isEmpty()) {
				store.recordPreparationProblem(candidate,
						VerificationPrecheckResult.rejected("IDENTITY_MAPPING_REQUIRED"));

				return;
			}

			boolean inserted = store.enqueue(request, result, mapping.get().fixtureIdentityId());

			if (inserted) {
				log.info("Identity job prepared: verificationId={}", candidate.verificationId());

			} else {
				store.recordPreparationProblem(candidate,
						VerificationPrecheckResult.retry("IDENTITY_PREPARATION_STATE_CHANGED"));
			}

		} catch (DuplicateKeyException exception) {
			// Another instance already created this identity job.
			log.debug("Identity job already prepared: verificationId={}", candidate.verificationId());

		} catch (Exception exception) {
			log.warn("Identity preparation failed: " + "verificationId={}, errorType={}", candidate.verificationId(),
					exception.getClass().getSimpleName());

			store.recordPreparationProblem(candidate, VerificationPrecheckResult.retry("IDENTITY_PREPARATION_ERROR"));
		}
	}
}