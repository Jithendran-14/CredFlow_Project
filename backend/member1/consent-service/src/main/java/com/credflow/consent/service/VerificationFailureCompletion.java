package com.credflow.consent.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.service.VerificationResultEventBuilder.ResultData;

import tools.jackson.databind.ObjectMapper;

@Service
public class VerificationFailureCompletion {

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final VerificationEventOutbox outbox;
	private final boolean publishWithoutFixture;

	public VerificationFailureCompletion(JdbcTemplate jdbc, ObjectMapper mapper, VerificationEventOutbox outbox,
			@Value("${credflow.verification-failures."
					+ "publish-without-fixture:false}") boolean publishWithoutFixture) {

		this.jdbc = jdbc;
		this.mapper = mapper;
		this.outbox = outbox;
		this.publishWithoutFixture = publishWithoutFixture;
	}

	private record Stored(String payload, String processingStatus, String precheckStatus, String precheckReason) {
	}

	private record Identity(String status, String fixture) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID verificationId) {

		var requests = jdbc.query("""
				SELECT
				    REQUEST_PAYLOAD,
				    PROCESSING_STATUS,
				    PRECHECK_STATUS,
				    PRECHECK_REASON
				FROM CF_VERIFICATION_REQUESTS
				WHERE VERIFICATION_ID = ?
				FOR UPDATE
				""", (rs, rowNumber) -> new Stored(rs.getString("REQUEST_PAYLOAD"), rs.getString("PROCESSING_STATUS"),
				rs.getString("PRECHECK_STATUS"), rs.getString("PRECHECK_REASON")), verificationId.toString());

		if (requests.isEmpty()) {
			return;
		}

		Stored stored = requests.getFirst();

		if ("COMPLETED".equals(stored.processingStatus())) {
			return;
		}

		if (!"PENDING".equals(stored.processingStatus()) && !"DEAD".equals(stored.processingStatus())) {
			return;
		}

		var identities = jdbc.query("""
				SELECT CHECK_STATUS, FIXTURE_IDENTITY_ID
				FROM CF_IDENTITY_CHECKS
				WHERE VERIFICATION_ID = ?
				""", (rs, rowNumber) -> new Identity(rs.getString("CHECK_STATUS"), rs.getString("FIXTURE_IDENTITY_ID")),
				verificationId.toString());

		Identity identity = identities.isEmpty() ? null : identities.getFirst();

		boolean rejected = "REJECTED".equals(stored.precheckStatus());

		boolean exhausted = "DEAD".equals(stored.processingStatus())
				|| (identity != null && "DEAD".equals(identity.status()));

		if (!rejected && !exhausted) {
			return;
		}

		String fixture = identity == null ? null : identity.fixture();

		if (fixture == null && !publishWithoutFixture) {
			jdbc.update("""
					UPDATE CF_VERIFICATION_REQUESTS
					SET LAST_FINALIZATION_ERROR =
					        'FIXTURELESS_CONTRACT_PENDING',
					    NEXT_ATTEMPT_AT =
					        SYSTIMESTAMP
					        + NUMTODSINTERVAL(60, 'SECOND')
					WHERE VERIFICATION_ID = ?
					""", verificationId.toString());
			return;
		}

		VerificationRequest request = mapper.readValue(stored.payload(), VerificationRequest.class);

		if (!verificationId.equals(request.verificationId())) {
			throw new IllegalStateException("VERIFICATION_REQUEST_ID_MISMATCH");
		}

		String reason = rejected ? stored.precheckReason() : "VERIFICATION_PROCESSING_UNAVAILABLE";

		if (reason == null || reason.isBlank()) {
			reason = "VERIFICATION_PROCESSING_UNAVAILABLE";
		}

		OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);

		jdbc.update("""
				INSERT INTO CF_VERIFICATION_RESULTS (
				    VERIFICATION_ID,
				    APPLICATION_ID,
				    APPLICATION_REVISION,
				    CONSENT_ID,
				    CONSENT_VERSION,
				    RESULT_SOURCE,
				    RESULT_STATUS,
				    REASON_CODE,
				    FIXTURE_IDENTITY_ID,
				    CHECKED_AT
				)
				VALUES (?, ?, ?, ?, ?, 'PRECHECK',
				        'ACTION_REQUIRED', ?, ?, ?)
				""", verificationId.toString(), request.applicationId().toString(), request.applicationRevision(),
				request.consentId().toString(), request.consentVersion(), reason, fixture, checkedAt);

		outbox.append(request, new ResultData("ACTION_REQUIRED", reason, checkedAt, fixture, null, null));

		jdbc.update("""
				UPDATE CF_VERIFICATION_REQUESTS
				SET PROCESSING_STATUS = 'COMPLETED',
				    COMPLETED_AT = SYSTIMESTAMP,
				    CLAIM_TOKEN = NULL,
				    LEASE_EXPIRES_AT = NULL,
				    LAST_FINALIZATION_ERROR = NULL,
				    UPDATED_AT = SYSTIMESTAMP
				WHERE VERIFICATION_ID = ?
				""", verificationId.toString());
	}
}