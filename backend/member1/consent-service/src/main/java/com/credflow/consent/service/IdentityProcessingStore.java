package com.credflow.consent.service;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.consent.dto.IdentityCheckRequest;
import com.credflow.consent.dto.IdentityCheckResponse;
import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;

@Service
public class IdentityProcessingStore {

    private final JdbcTemplate jdbc;

    public IdentityProcessingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Candidate(
            String verificationId,
            String payload
    ) {
    }

    public record Claim(
            IdentityCheckRequest input,
            String claimToken,
            String verificationPayload,
            String correlationId,
            int attemptCount
    ) {
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public List<Candidate> findReadyRequests() {

        return jdbc.query("""
                SELECT r.VERIFICATION_ID, r.REQUEST_PAYLOAD
                FROM CF_VERIFICATION_REQUESTS r
                WHERE r.PRECHECK_STATUS = 'READY'
                  AND r.PROCESSING_STATUS = 'PENDING'
                  AND r.NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                  AND NOT EXISTS (
                      SELECT 1
                      FROM CF_IDENTITY_CHECKS i
                      WHERE i.VERIFICATION_ID = r.VERIFICATION_ID
                  )
                ORDER BY r.CREATED_AT, r.VERIFICATION_ID
                FETCH FIRST 10 ROWS ONLY
                """,
                (rs, rowNumber) -> new Candidate(
                        rs.getString("VERIFICATION_ID"),
                        rs.getString("REQUEST_PAYLOAD")
                )
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueue(
            VerificationRequest request,
            VerificationPrecheckResult precheck,
            String fixtureIdentityId) {

        int inserted = jdbc.update("""
                INSERT INTO CF_IDENTITY_CHECKS (
                    VERIFICATION_ID,
                    APPLICATION_ID,
                    APPLICATION_REVISION,
                    FIXTURE_IDENTITY_ID
                )
                SELECT
                    r.VERIFICATION_ID,
                    r.APPLICATION_ID,
                    r.APPLICATION_REVISION,
                    m.FIXTURE_IDENTITY_ID
                FROM CF_VERIFICATION_REQUESTS r
                JOIN CF_CONSENTS c
                  ON c.CONSENT_ID = r.CONSENT_ID
                 AND c.APPLICATION_ID = r.APPLICATION_ID
                 AND c.CONSENT_VERSION = r.CONSENT_VERSION
                 AND c.CONSENT_STATUS = 'GRANTED'
                JOIN CF_SELLER_IDENTITY_MAP m
                  ON m.SELLER_ID = c.SELLER_ID
                 AND m.MARKETPLACE_ID = ?
                 AND m.MAPPING_STATUS = 'ACTIVE'
                WHERE r.VERIFICATION_ID = ?
                  AND r.APPLICATION_ID = ?
                  AND r.APPLICATION_REVISION = ?
                  AND r.PRECHECK_STATUS = 'READY'
                  AND r.PROCESSING_STATUS = 'PENDING'
                  AND c.SELLER_ID = ?
                  AND m.FIXTURE_IDENTITY_ID = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM CF_IDENTITY_CHECKS i
                      WHERE i.VERIFICATION_ID = r.VERIFICATION_ID
                  )
                """,
                precheck.marketplaceId().toString(),
                request.verificationId().toString(),
                request.applicationId().toString(),
                request.applicationRevision(),
                precheck.sellerId(),
                fixtureIdentityId
        );

        return inserted == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPreparationProblem(
            Candidate candidate,
            VerificationPrecheckResult result) {

        boolean rejected =
                result.outcome()
                        == VerificationPrecheckResult.Outcome.REJECTED;

        jdbc.update("""
                UPDATE CF_VERIFICATION_REQUESTS r
                SET PRECHECK_STATUS = ?,
                    PRECHECK_REASON = ?,
                    NEXT_ATTEMPT_AT =
                        SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND'),
                    UPDATED_AT = SYSTIMESTAMP
                WHERE r.VERIFICATION_ID = ?
                  AND r.PRECHECK_STATUS = 'READY'
                  AND r.PROCESSING_STATUS = 'PENDING'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM CF_IDENTITY_CHECKS i
                      WHERE i.VERIFICATION_ID = r.VERIFICATION_ID
                  )
                """,
                rejected ? "REJECTED" : "READY",
                result.reasonCode(),
                rejected ? 0 : 60,
                candidate.verificationId()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext() {

        List<String> candidates = jdbc.queryForList("""
                SELECT VERIFICATION_ID
                FROM CF_IDENTITY_CHECKS
                WHERE (
                    CHECK_STATUS = 'PENDING'
                    AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                )
                OR (
                    CHECK_STATUS = 'PROCESSING'
                    AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
                )
                ORDER BY CREATED_AT, VERIFICATION_ID
                FETCH FIRST 10 ROWS ONLY
                """, String.class);

        for (String verificationId : candidates) {
            String claimToken = UUID.randomUUID().toString();

            int claimed = jdbc.update("""
                    UPDATE CF_IDENTITY_CHECKS
                    SET CHECK_STATUS = 'PROCESSING',
                        CLAIM_TOKEN = ?,
                        LEASE_EXPIRES_AT =
                            SYSTIMESTAMP
                            + NUMTODSINTERVAL(60, 'SECOND'),
                        ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                        UPDATED_AT = SYSTIMESTAMP
                    WHERE VERIFICATION_ID = ?
                      AND (
                          (
                              CHECK_STATUS = 'PENDING'
                              AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                          )
                          OR (
                              CHECK_STATUS = 'PROCESSING'
                              AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
                          )
                      )
                    """,
                    claimToken,
                    verificationId
            );

            if (claimed != 1) {
                continue;
            }

            Claim result = jdbc.queryForObject("""
                    SELECT
                        i.VERIFICATION_ID,
                        i.APPLICATION_ID,
                        i.APPLICATION_REVISION,
                        i.FIXTURE_IDENTITY_ID,
                        i.CLAIM_TOKEN,
                        i.ATTEMPT_COUNT,
                        r.REQUEST_PAYLOAD,
                        r.CORRELATION_ID
                    FROM CF_IDENTITY_CHECKS i
                    JOIN CF_VERIFICATION_REQUESTS r
                      ON r.VERIFICATION_ID = i.VERIFICATION_ID
                    WHERE i.VERIFICATION_ID = ?
                      AND i.CLAIM_TOKEN = ?
                    """,
                    (rs, rowNumber) -> new Claim(
                            new IdentityCheckRequest(
                                    UUID.fromString(
                                            rs.getString("VERIFICATION_ID")),
                                    UUID.fromString(
                                            rs.getString("APPLICATION_ID")),
                                    rs.getInt("APPLICATION_REVISION"),
                                    rs.getString("FIXTURE_IDENTITY_ID")
                            ),
                            rs.getString("CLAIM_TOKEN"),
                            rs.getString("REQUEST_PAYLOAD"),
                            rs.getString("CORRELATION_ID"),
                            rs.getInt("ATTEMPT_COUNT")
                    ),
                    verificationId,
                    claimToken
            );

            return Optional.ofNullable(result);
        }

        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordReceived(
            Claim claim,
            IdentityCheckResponse response,
            String responseJson) {

        int updated = jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE CF_IDENTITY_CHECKS
                    SET CHECK_STATUS = 'RECEIVED',
                        PROVIDER_STATUS = ?,
                        PROVIDER_REFERENCE = ?,
                        PROVIDER_REASON_CODE = ?,
                        PROVIDER_CHECKED_AT = ?,
                        PROVIDER_RESPONSE = ?,
                        LAST_ERROR = NULL,
                        CLAIM_TOKEN = NULL,
                        LEASE_EXPIRES_AT = NULL,
                        UPDATED_AT = SYSTIMESTAMP
                    WHERE VERIFICATION_ID = ?
                      AND CHECK_STATUS = 'PROCESSING'
                      AND CLAIM_TOKEN = ?
                    """);

            statement.setString(1, response.status().name());
            statement.setString(2, response.providerReference());
            statement.setString(3, response.reasonCode());
            statement.setObject(4, response.checkedAt());

            statement.setCharacterStream(
                    5,
                    new StringReader(responseJson),
                    responseJson.length()
            );

            statement.setString(
                    6,
                    claim.input().verificationRequestId().toString()
            );

            statement.setString(7, claim.claimToken());

            return statement;
        });

        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            Claim claim,
            String errorCode,
            boolean stopRetrying) {

        boolean dead = stopRetrying || claim.attemptCount() >= 20;

        int delaySeconds = (int) Math.min(
                300L,
                5L * (1L << Math.min(
                        claim.attemptCount() - 1,
                        6
                ))
        );

        jdbc.update("""
                UPDATE CF_IDENTITY_CHECKS
                SET CHECK_STATUS = ?,
                    LAST_ERROR = ?,
                    NEXT_ATTEMPT_AT =
                        SYSTIMESTAMP
                        + NUMTODSINTERVAL(?, 'SECOND'),
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL,
                    UPDATED_AT = SYSTIMESTAMP
                WHERE VERIFICATION_ID = ?
                  AND CHECK_STATUS = 'PROCESSING'
                  AND CLAIM_TOKEN = ?
                """,
                dead ? "DEAD" : "PENDING",
                errorCode,
                delaySeconds,
                claim.input().verificationRequestId().toString(),
                claim.claimToken()
        );
    }
}