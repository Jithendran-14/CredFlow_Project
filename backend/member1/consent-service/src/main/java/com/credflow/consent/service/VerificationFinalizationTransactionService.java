package com.credflow.consent.service;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.consent.dto.IdentityCheckResponse;
import com.credflow.consent.dto.VerificationPrecheckResult;
import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.service.VerificationResultEventBuilder.ResultData;

import tools.jackson.databind.ObjectMapper;

@Service
public class VerificationFinalizationTransactionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VerificationResultEventBuilder eventBuilder;
    private final String authorisedClientId;
    private final long retrievalTtlSeconds;

    public VerificationFinalizationTransactionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            VerificationResultEventBuilder eventBuilder,
            @Value(
                "${credflow.verification-finalization."
                + "retrieval-authorised-client-id}"
            )
            String authorisedClientId,
            @Value(
                "${credflow.verification-finalization."
                + "retrieval-ttl-seconds}"
            )
            long retrievalTtlSeconds) {

        if (authorisedClientId.isBlank()
                || authorisedClientId.length() > 100
                || retrievalTtlSeconds < 60
                || retrievalTtlSeconds > 86400) {

            throw new IllegalArgumentException(
                    "INVALID_RETRIEVAL_AUTHORIZATION_CONFIGURATION"
            );
        }

        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.eventBuilder = eventBuilder;
        this.authorisedClientId = authorisedClientId;
        this.retrievalTtlSeconds = retrievalTtlSeconds;
    }

    private record StoredRequest(
            String payload,
            String processingStatus,
            OffsetDateTime createdAt
    ) {
    }

    private record ProviderResult(
            String applicationId,
            int applicationRevision,
            String fixtureIdentityId,
            String status,
            String reasonCode,
            String providerReference,
            OffsetDateTime checkedAt
    ) {
    }

    private record FinancialPermission(
            String consentId,
            int consentVersion,
            OffsetDateTime expiresAt
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeProviderResult(
            UUID verificationId,
            VerificationPrecheckResult currentPrecheck) {

        if (currentPrecheck.outcome()
                == VerificationPrecheckResult.Outcome.RETRY_LATER) {
            return;
        }

        // Serialize finalisation attempts for this verification.
        var requests = jdbc.query("""
                SELECT
                    REQUEST_PAYLOAD,
                    PROCESSING_STATUS,
                    CREATED_AT
                FROM CF_VERIFICATION_REQUESTS
                WHERE VERIFICATION_ID = ?
                FOR UPDATE
                """,
                (rs, rowNumber) -> new StoredRequest(
                        rs.getString("REQUEST_PAYLOAD"),
                        rs.getString("PROCESSING_STATUS"),
                        rs.getObject(
                                "CREATED_AT",
                                OffsetDateTime.class
                        )
                ),
                verificationId.toString()
        );

        if (requests.isEmpty()) {
            throw new IllegalStateException(
                    "VERIFICATION_REQUEST_NOT_FOUND"
            );
        }

        StoredRequest stored = requests.getFirst();

        if (!"PENDING".equals(stored.processingStatus())) {
            return;
        }

        VerificationRequest request = objectMapper.readValue(
                stored.payload(),
                VerificationRequest.class
        );

        if (!verificationId.equals(request.verificationId())) {
            throw new IllegalStateException(
                    "VERIFICATION_REQUEST_ID_MISMATCH"
            );
        }

        var providerResults = jdbc.query("""
                SELECT
                    APPLICATION_ID,
                    APPLICATION_REVISION,
                    FIXTURE_IDENTITY_ID,
                    PROVIDER_STATUS,
                    PROVIDER_REASON_CODE,
                    PROVIDER_REFERENCE,
                    PROVIDER_CHECKED_AT
                FROM CF_IDENTITY_CHECKS
                WHERE VERIFICATION_ID = ?
                  AND CHECK_STATUS = 'RECEIVED'
                """,
                (rs, rowNumber) -> new ProviderResult(
                        rs.getString("APPLICATION_ID"),
                        rs.getInt("APPLICATION_REVISION"),
                        rs.getString("FIXTURE_IDENTITY_ID"),
                        rs.getString("PROVIDER_STATUS"),
                        rs.getString("PROVIDER_REASON_CODE"),
                        rs.getString("PROVIDER_REFERENCE"),
                        rs.getObject(
                                "PROVIDER_CHECKED_AT",
                                OffsetDateTime.class
                        )
                ),
                verificationId.toString()
        );

        if (providerResults.isEmpty()) {
            return;
        }

        ProviderResult provider = providerResults.getFirst();

        if (!request.applicationId().toString().equals(
                    provider.applicationId())
                || request.applicationRevision()
                        != provider.applicationRevision()
                || provider.checkedAt() == null
                || provider.fixtureIdentityId() == null
                || provider.fixtureIdentityId().isBlank()) {

            throw new IllegalStateException(
                    "INVALID_STORED_IDENTITY_RESULT"
            );
        }

        String status = IdentityCheckResponse.Status
                .valueOf(provider.status())
                .name();

        String reason = provider.reasonCode();
        String source = "PROVIDER";
        OffsetDateTime checkedAt = provider.checkedAt();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        
        if ("PENDING".equals(status)) {
            if (stored.createdAt().plusMinutes(5).isAfter(now)) {
                // Keep this attempt waiting; do not publish a final result yet.
                return;
            }

            status = "ACTION_REQUIRED";
            reason = "VERIFICATION_PENDING_TIMEOUT";
            source = "PRECHECK";
            checkedAt = now;
        }

        if (currentPrecheck.outcome()
                == VerificationPrecheckResult.Outcome.REJECTED) {

            status = "ACTION_REQUIRED";
            reason = currentPrecheck.reasonCode();
            source = "PRECHECK";
            checkedAt = now;

        } else {
            // Keep this lock until result, authorisation and event commit.
            var validConsents = jdbc.query("""
                    SELECT CONSENT_ID
                    FROM CF_CONSENTS
                    WHERE CONSENT_ID = ?
                      AND APPLICATION_ID = ?
                      AND CONSENT_VERSION = ?
                      AND CONSENT_STATUS = 'GRANTED'
                      AND SELLER_ID = ?
                    FOR UPDATE
                    """,
                    (rs, rowNumber) -> rs.getString("CONSENT_ID"),
                    request.consentId().toString(),
                    request.applicationId().toString(),
                    request.consentVersion(),
                    currentPrecheck.sellerId()
            );

            if (validConsents.isEmpty()) {
                status = "ACTION_REQUIRED";
                reason = "CONSENT_CHANGED_BEFORE_FINALIZATION";
                source = "PRECHECK";
                checkedAt = now;

            } else if ("VERIFIED".equals(status)) {
                var activeMappings = jdbc.query("""
                        SELECT FIXTURE_IDENTITY_ID
                        FROM CF_SELLER_IDENTITY_MAP
                        WHERE SELLER_ID = ?
                          AND MARKETPLACE_ID = ?
                          AND MAPPING_STATUS = 'ACTIVE'
                        FOR UPDATE
                        """,
                        (rs, rowNumber) ->
                                rs.getString("FIXTURE_IDENTITY_ID"),
                        currentPrecheck.sellerId(),
                        currentPrecheck.marketplaceId().toString()
                );

                if (activeMappings.isEmpty()
                        || !provider.fixtureIdentityId().equals(
                                activeMappings.getFirst())) {

                    status = "ACTION_REQUIRED";
                    reason = "IDENTITY_MAPPING_CHANGED_OR_DISABLED";
                    source = "PRECHECK";
                    checkedAt = now;
                }
            }
        }

        String financialConsentId = null;
        Integer financialConsentVersion = null;

        OffsetDateTime authorizationExpiresAt =
                now.plusSeconds(retrievalTtlSeconds);

        // Operational consent alone does not issue financial authority.
        if ("VERIFIED".equals(status)) {
            var permissions = jdbc.query("""
                    SELECT
                        f.CONSENT_ID,
                        f.CONSENT_VERSION,
                        f.EXPIRES_AT
                    FROM CF_FINANCIAL_CONSENTS f
                    WHERE f.APPLICATION_ID = ?
                      AND f.APPLICATION_REVISION = ?
                      AND f.OPERATIONAL_CONSENT_ID = ?
                      AND f.OPERATIONAL_CONSENT_VERSION = ?
                      AND f.SELLER_ID = ?
                      AND f.MARKETPLACE_ID = ?
                      AND f.CONSENT_SCOPE =
                          'FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY'
                      AND f.CONSENT_STATUS = 'ACTIVE'
                      AND f.GRANTED_AT <= SYSTIMESTAMP
                      AND f.EXPIRES_AT > SYSTIMESTAMP
                      AND f.CONSENT_VERSION = (
                          SELECT MAX(latest.CONSENT_VERSION)
                          FROM CF_FINANCIAL_CONSENTS latest
                          WHERE latest.APPLICATION_ID =
                                f.APPLICATION_ID
                            AND latest.APPLICATION_REVISION =
                                f.APPLICATION_REVISION
                      )
                    FOR UPDATE
                    """,
                    (rs, rowNumber) -> new FinancialPermission(
                            rs.getString("CONSENT_ID"),
                            rs.getInt("CONSENT_VERSION"),
                            rs.getObject(
                                    "EXPIRES_AT",
                                    OffsetDateTime.class
                            )
                    ),
                    request.applicationId().toString(),
                    request.applicationRevision(),
                    request.consentId().toString(),
                    request.consentVersion(),
                    currentPrecheck.sellerId(),
                    currentPrecheck.marketplaceId().toString()
            );

            if (permissions.isEmpty()) {
                status = "ACTION_REQUIRED";
                reason = "FINANCIAL_CONSENT_REQUIRED";
                source = "PRECHECK";
                checkedAt = now;

            } else {
                FinancialPermission permission =
                        permissions.getFirst();

                financialConsentId = permission.consentId();
                financialConsentVersion =
                        permission.consentVersion();

                if (permission.expiresAt().isBefore(
                        authorizationExpiresAt)) {

                    authorizationExpiresAt =
                            permission.expiresAt();
                }
            }
        }

        String retrievalReference =
                "VERIFIED".equals(status)
                        ? UUID.randomUUID().toString()
                        : null;

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
                    PROVIDER_REFERENCE,
                    CHECKED_AT
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                verificationId.toString(),
                request.applicationId().toString(),
                request.applicationRevision(),
                request.consentId().toString(),
                request.consentVersion(),
                source,
                status,
                reason,
                provider.fixtureIdentityId(),
                provider.providerReference(),
                checkedAt
        );

        if (retrievalReference != null) {
            jdbc.update("""
                    INSERT INTO CF_RETRIEVAL_AUTHORIZATIONS (
                        VERIFICATION_ID,
                        AUTHORISED_RETRIEVAL_REFERENCE,
                        APPLICATION_ID,
                        APPLICATION_REVISION,
                        CONSENT_ID,
                        CONSENT_VERSION,
                        FINANCIAL_CONSENT_ID,
                        FINANCIAL_CONSENT_VERSION,
                        SELLER_ID,
                        MARKETPLACE_ID,
                        AUTHORISED_CLIENT_ID,
                        PURPOSE,
                        ISSUED_AT,
                        EXPIRES_AT
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    verificationId.toString(),
                    retrievalReference,
                    request.applicationId().toString(),
                    request.applicationRevision(),
                    request.consentId().toString(),
                    request.consentVersion(),
                    financialConsentId,
                    financialConsentVersion,
                    currentPrecheck.sellerId(),
                    currentPrecheck.marketplaceId().toString(),
                    authorisedClientId,
                    "ELIGIBILITY_EVALUATION",
                    now,
                    authorizationExpiresAt
            );
        }

        long eventVersion = nextEventVersion(
                request.applicationId().toString()
        );

        UUID eventId = UUID.randomUUID();

        String eventJson = eventBuilder.build(
                request,
                new ResultData(
                        status,
                        reason,
                        checkedAt,
                        provider.fixtureIdentityId(),
                        provider.providerReference(),
                        retrievalReference
                ),
                eventId,
                eventVersion,
                now
        );

        insertOutbox(
                request,
                eventId,
                eventVersion,
                eventJson
        );

        int completed = jdbc.update("""
                UPDATE CF_VERIFICATION_REQUESTS
                SET PROCESSING_STATUS = 'COMPLETED',
                    COMPLETED_AT = SYSTIMESTAMP,
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL,
                    UPDATED_AT = SYSTIMESTAMP
                WHERE VERIFICATION_ID = ?
                  AND PROCESSING_STATUS = 'PENDING'
                """,
                verificationId.toString()
        );

        if (completed != 1) {
            throw new IllegalStateException(
                    "VERIFICATION_COMPLETION_UPDATE_FAILED"
            );
        }
    }

    private long nextEventVersion(String applicationId) {

        jdbc.update("""
                MERGE INTO CF_CONSENT_EVENT_COUNTERS target
                USING (
                    SELECT ? AS APPLICATION_ID FROM DUAL
                ) source
                ON (
                    target.APPLICATION_ID = source.APPLICATION_ID
                )
                WHEN NOT MATCHED THEN
                    INSERT (APPLICATION_ID, LAST_EVENT_VERSION)
                    VALUES (source.APPLICATION_ID, 0)
                """,
                applicationId
        );

        Long previous = jdbc.queryForObject("""
                SELECT LAST_EVENT_VERSION
                FROM CF_CONSENT_EVENT_COUNTERS
                WHERE APPLICATION_ID = ?
                FOR UPDATE
                """,
                Long.class,
                applicationId
        );

        if (previous == null) {
            throw new IllegalStateException(
                    "CONSENT_EVENT_COUNTER_MISSING"
            );
        }

        long next = Math.addExact(previous, 1L);

        jdbc.update("""
                UPDATE CF_CONSENT_EVENT_COUNTERS
                SET LAST_EVENT_VERSION = ?
                WHERE APPLICATION_ID = ?
                """,
                next,
                applicationId
        );

        return next;
    }

    private void insertOutbox(
            VerificationRequest request,
            UUID eventId,
            long eventVersion,
            String eventJson) {

        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO CF_OUTBOX_EVENTS (
                        EVENT_ID,
                        VERIFICATION_ID,
                        APPLICATION_ID,
                        AGGREGATE_VERSION,
                        EVENT_TYPE,
                        SCHEMA_VERSION,
                        CORRELATION_ID,
                        CAUSATION_ID,
                        EVENT_PAYLOAD
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);

            statement.setString(1, eventId.toString());

            statement.setString(
                    2,
                    request.verificationId().toString()
            );

            statement.setString(
                    3,
                    request.applicationId().toString()
            );

            statement.setLong(4, eventVersion);
            statement.setString(5, "verification.completed");
            statement.setString(6, "1.0");
            statement.setString(7, request.correlationId());

            statement.setString(
                    8,
                    request.causationId() == null
                            ? null
                            : request.causationId().toString()
            );

            statement.setCharacterStream(
                    9,
                    new StringReader(eventJson),
                    eventJson.length()
            );

            return statement;
        });
    }
}