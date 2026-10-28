package com.credflow.consent.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.consent.dto.VerificationPrecheckResult;

@Service
public class VerificationPrecheckStore {

	private final JdbcTemplate jdbc;

	public VerificationPrecheckStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record ClaimedRequest(String verificationId, String claimToken, String payload, int attemptCount) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<ClaimedRequest> claimNext() {

		List<String> candidates = jdbc.queryForList("""
				SELECT VERIFICATION_ID
				FROM CF_VERIFICATION_REQUESTS
				WHERE PRECHECK_STATUS = 'PENDING'
				  AND (
				      (
				          PROCESSING_STATUS = 'PENDING'
				          AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
				      )
				      OR (
				          PROCESSING_STATUS = 'PROCESSING'
				          AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
				      )
				  )
				ORDER BY CREATED_AT, VERIFICATION_ID
				FETCH FIRST 10 ROWS ONLY
				""", String.class);

		for (String verificationId : candidates) {
			String claimToken = UUID.randomUUID().toString();

			int claimed = jdbc.update("""
					UPDATE CF_VERIFICATION_REQUESTS
					SET PROCESSING_STATUS = 'PROCESSING',
					    CLAIM_TOKEN = ?,
					    LEASE_EXPIRES_AT =
					        SYSTIMESTAMP
					        + NUMTODSINTERVAL(60, 'SECOND'),
					    ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
					    UPDATED_AT = SYSTIMESTAMP
					WHERE VERIFICATION_ID = ?
					  AND PRECHECK_STATUS = 'PENDING'
					  AND (
					      (
					          PROCESSING_STATUS = 'PENDING'
					          AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
					      )
					      OR (
					          PROCESSING_STATUS = 'PROCESSING'
					          AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
					      )
					  )
					""", claimToken, verificationId);

			if (claimed != 1) {
				continue;
			}

			ClaimedRequest request = jdbc.queryForObject(
					"""
							SELECT
							    VERIFICATION_ID,
							    CLAIM_TOKEN,
							    REQUEST_PAYLOAD,
							    ATTEMPT_COUNT
							FROM CF_VERIFICATION_REQUESTS
							WHERE VERIFICATION_ID = ?
							  AND CLAIM_TOKEN = ?
							""", (rs, rowNumber) -> new ClaimedRequest(rs.getString("VERIFICATION_ID"),
							rs.getString("CLAIM_TOKEN"), rs.getString("REQUEST_PAYLOAD"), rs.getInt("ATTEMPT_COUNT")),
					verificationId, claimToken);

			return Optional.ofNullable(request);
		}

		return Optional.empty();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean saveOutcome(ClaimedRequest request, VerificationPrecheckResult result) {

		if (result.outcome() == VerificationPrecheckResult.Outcome.RETRY_LATER) {

			return scheduleRetry(request, result.reasonCode());
		}

		String precheckStatus = switch (result.outcome()) {
		case READY_FOR_IDENTITY_CHECK -> "READY";
		case REJECTED -> "REJECTED";
		default -> throw new IllegalArgumentException("Unsupported precheck outcome");
		};

		int updated = jdbc.update("""
				UPDATE CF_VERIFICATION_REQUESTS
				SET PRECHECK_STATUS = ?,
				    PRECHECK_REASON = ?,
				    PRECHECKED_AT = SYSTIMESTAMP,
				    PROCESSING_STATUS = 'PENDING',
				    CLAIM_TOKEN = NULL,
				    LEASE_EXPIRES_AT = NULL,
				    UPDATED_AT = SYSTIMESTAMP
				WHERE VERIFICATION_ID = ?
				  AND PRECHECK_STATUS = 'PENDING'
				  AND PROCESSING_STATUS = 'PROCESSING'
				  AND CLAIM_TOKEN = ?
				""", precheckStatus, result.reasonCode(), request.verificationId(), request.claimToken());

		return updated == 1;
	}

	private boolean scheduleRetry(ClaimedRequest request, String reasonCode) {

		boolean exhausted = request.attemptCount() >= 20;

		int delaySeconds = (int) Math.min(300L, 5L * (1L << Math.min(request.attemptCount() - 1, 6)));

		int updated = jdbc.update("""
				UPDATE CF_VERIFICATION_REQUESTS
				SET PROCESSING_STATUS = ?,
				    PRECHECK_REASON = ?,
				    NEXT_ATTEMPT_AT =
				        SYSTIMESTAMP
				        + NUMTODSINTERVAL(?, 'SECOND'),
				    CLAIM_TOKEN = NULL,
				    LEASE_EXPIRES_AT = NULL,
				    UPDATED_AT = SYSTIMESTAMP
				WHERE VERIFICATION_ID = ?
				  AND PRECHECK_STATUS = 'PENDING'
				  AND PROCESSING_STATUS = 'PROCESSING'
				  AND CLAIM_TOKEN = ?
				""", exhausted ? "DEAD" : "PENDING", reasonCode, delaySeconds, request.verificationId(),
				request.claimToken());

		return updated == 1;
	}
}