package com.credflow.consent.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;

import jakarta.validation.Validator;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "credflow.verification-precheck.enabled", havingValue = "true")
public class VerificationPrecheckWorker {

	private static final Logger log = LoggerFactory.getLogger(VerificationPrecheckWorker.class);

	private final VerificationPrecheckStore store;
	private final VerificationPrecheckService prechecks;
	private final ObjectMapper objectMapper;
	private final Validator validator;

	public VerificationPrecheckWorker(VerificationPrecheckStore store, VerificationPrecheckService prechecks,
			ObjectMapper objectMapper, Validator validator) {

		this.store = store;
		this.prechecks = prechecks;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	@Scheduled(fixedDelayString = "${credflow.verification-precheck.fixed-delay-ms:5000}")
	public void processNext() {

		try {
			var candidate = store.claimNext();

			if (candidate.isEmpty()) {
				return;
			}

			var claimed = candidate.get();

			VerificationPrecheckResult result;

			try {
				VerificationRequest request = objectMapper.readValue(claimed.payload(), VerificationRequest.class);

				if (request == null || !validator.validate(request).isEmpty()
						|| !Objects.equals(claimed.verificationId(), request.verificationId().toString())) {

					throw new IllegalStateException("INVALID_STORED_VERIFICATION_REQUEST");
				}

				result = prechecks.check(request);

			} catch (Exception exception) {
				result = VerificationPrecheckResult.retry("PRECHECK_PROCESSING_ERROR");

				log.warn("Verification precheck failed: " + "verificationId={}, errorType={}", claimed.verificationId(),
						exception.getClass().getSimpleName());
			}

			boolean saved = store.saveOutcome(claimed, result);

			if (saved) {
				log.info("Verification precheck recorded: " + "verificationId={}, outcome={}, reason={}",
						claimed.verificationId(), result.outcome(), result.reasonCode());
			} else {
				log.info("Verification precheck claim no longer current: " + "verificationId={}",
						claimed.verificationId());
			}

		} catch (Exception exception) {
			log.error("Verification precheck worker failed: errorType={}", exception.getClass().getSimpleName());
		}
	}
}