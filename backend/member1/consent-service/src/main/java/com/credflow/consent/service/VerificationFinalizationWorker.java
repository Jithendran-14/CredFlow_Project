package com.credflow.consent.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;

import jakarta.validation.Validator;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "credflow.verification-finalization.enabled", havingValue = "true")
public class VerificationFinalizationWorker {

	private static final Logger log = LoggerFactory.getLogger(VerificationFinalizationWorker.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final Validator validator;
	private final VerificationPrecheckService prechecks;
	private final VerificationFinalizationTransactionService finalization;

	public VerificationFinalizationWorker(JdbcTemplate jdbc, ObjectMapper objectMapper, Validator validator,
			VerificationPrecheckService prechecks, VerificationFinalizationTransactionService finalization) {

		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.validator = validator;
		this.prechecks = prechecks;
		this.finalization = finalization;
	}

	private record Candidate(String verificationId, String payload) {
	}

	@Scheduled(fixedDelayString = "${credflow.verification-finalization.fixed-delay-ms:5000}")
	public void finalizeReadyResults() {

		try {
			var candidates = jdbc.query("""
					SELECT
					    r.VERIFICATION_ID,
					    r.REQUEST_PAYLOAD
					FROM CF_VERIFICATION_REQUESTS r
					JOIN CF_IDENTITY_CHECKS i
					  ON i.VERIFICATION_ID = r.VERIFICATION_ID
					WHERE r.PROCESSING_STATUS = 'PENDING'
					  AND i.CHECK_STATUS = 'RECEIVED'
					  AND r.NEXT_ATTEMPT_AT <= SYSTIMESTAMP
					  AND NOT EXISTS (
					      SELECT 1
					      FROM CF_VERIFICATION_RESULTS result
					      WHERE result.VERIFICATION_ID =
					            r.VERIFICATION_ID
					  )
					ORDER BY r.UPDATED_AT, r.VERIFICATION_ID
					FETCH FIRST 10 ROWS ONLY
					""",
					(rs, rowNumber) -> new Candidate(rs.getString("VERIFICATION_ID"), rs.getString("REQUEST_PAYLOAD")));

			for (var candidate : candidates) {
				finalizeOne(candidate);
			}

		} catch (Exception exception) {
			log.error("Verification finalisation poll failed: errorType={}", exception.getClass().getSimpleName());
		}
	}

	private void finalizeOne(Candidate candidate) {

		try {
			VerificationRequest request = objectMapper.readValue(candidate.payload(), VerificationRequest.class);

			if (request == null || !validator.validate(request).isEmpty()
					|| !candidate.verificationId().equals(request.verificationId().toString())) {

				throw new IllegalStateException("INVALID_STORED_VERIFICATION_REQUEST");
			}

			// Remote application check happens before the final DB transaction.
			var result = prechecks.check(request);

			if (result.outcome() == VerificationPrecheckResult.Outcome.RETRY_LATER) {

				retryLater(candidate.verificationId(), result.reasonCode());
				return;
			}

			finalization.finalizeProviderResult(UUID.fromString(candidate.verificationId()), result);

		} catch (Exception exception) {
			retryLater(candidate.verificationId(), "FINALIZATION_PROCESSING_ERROR");

			log.warn("Verification finalisation failed: " + "verificationId={}, errorType={}",
					candidate.verificationId(), exception.getClass().getSimpleName());
		}
	}

	private void retryLater(String verificationId, String reason) {

		jdbc.update("""
				UPDATE CF_VERIFICATION_REQUESTS
				SET PROCESSING_STATUS =
				        CASE
				            WHEN FINALIZATION_ATTEMPTS + 1 >= 20
				            THEN 'DEAD'
				            ELSE 'PENDING'
				        END,
				    NEXT_ATTEMPT_AT =
				        SYSTIMESTAMP + NUMTODSINTERVAL(
				            LEAST(
				                300,
				                5 * POWER(
				                    2,
				                    LEAST(FINALIZATION_ATTEMPTS, 6)
				                )
				            ),
				            'SECOND'
				        ),
				    FINALIZATION_ATTEMPTS =
				        FINALIZATION_ATTEMPTS + 1,
				    LAST_FINALIZATION_ERROR = ?,
				    UPDATED_AT = SYSTIMESTAMP
				WHERE VERIFICATION_ID = ?
				  AND PROCESSING_STATUS = 'PENDING'
				""", reason, verificationId);
	}
}