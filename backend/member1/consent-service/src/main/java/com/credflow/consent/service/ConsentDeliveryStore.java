package com.credflow.consent.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentDeliveryStore {

    private final JdbcTemplate jdbc;

    public ConsentDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Delivery(
            String operationId,
            String claimToken,
            String consentId,
            int consentVersion,
            String applicationId,
            String correlationId,
            String payload,
            int attemptCount
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claimNext() {

        List<String> candidates = jdbc.queryForList("""
                SELECT d.OPERATION_ID
                FROM CF_CONSENT_DELIVERIES d
                JOIN CF_CONSENT_HISTORY h
                  ON h.OPERATION_ID = d.OPERATION_ID
                WHERE (
                    (
                        d.DELIVERY_STATUS = 'PENDING'
                        AND d.NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                    )
                    OR (
                        d.DELIVERY_STATUS = 'PROCESSING'
                        AND d.LEASE_EXPIRES_AT <= SYSTIMESTAMP
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM CF_CONSENT_HISTORY older
                    JOIN CF_CONSENT_DELIVERIES previous
                      ON previous.OPERATION_ID = older.OPERATION_ID
                    WHERE older.CONSENT_ID = h.CONSENT_ID
                      AND older.CONSENT_VERSION < h.CONSENT_VERSION
                      AND previous.DELIVERY_STATUS <> 'APPLIED'
                )
                ORDER BY d.CREATED_AT, d.OPERATION_ID
                FETCH FIRST 10 ROWS ONLY
                """, String.class);

        for (String operationId : candidates) {
            String claimToken = UUID.randomUUID().toString();

            int claimed = jdbc.update("""
                    UPDATE CF_CONSENT_DELIVERIES
                    SET DELIVERY_STATUS = 'PROCESSING',
                        CLAIM_TOKEN = ?,
                        LEASE_EXPIRES_AT =
                            SYSTIMESTAMP
                            + NUMTODSINTERVAL(60, 'SECOND'),
                        ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                        UPDATED_AT = SYSTIMESTAMP
                    WHERE OPERATION_ID = ?
                      AND (
                          (
                              DELIVERY_STATUS = 'PENDING'
                              AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                          )
                          OR (
                              DELIVERY_STATUS = 'PROCESSING'
                              AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
                          )
                      )
                    """, claimToken, operationId);

            if (claimed != 1) {
                continue;
            }

            Delivery delivery = jdbc.queryForObject("""
                    SELECT
                        d.OPERATION_ID,
                        d.CLAIM_TOKEN,
                        d.ATTEMPT_COUNT,
                        h.CONSENT_ID,
                        h.CONSENT_VERSION,
                        h.CORRELATION_ID,
                        h.CONSENT_PAYLOAD,
                        c.APPLICATION_ID
                    FROM CF_CONSENT_DELIVERIES d
                    JOIN CF_CONSENT_HISTORY h
                      ON h.OPERATION_ID = d.OPERATION_ID
                    JOIN CF_CONSENTS c
                      ON c.CONSENT_ID = h.CONSENT_ID
                    WHERE d.OPERATION_ID = ?
                      AND d.CLAIM_TOKEN = ?
                    """,
                    (rs, rowNumber) -> new Delivery(
                            rs.getString("OPERATION_ID"),
                            rs.getString("CLAIM_TOKEN"),
                            rs.getString("CONSENT_ID"),
                            rs.getInt("CONSENT_VERSION"),
                            rs.getString("APPLICATION_ID"),
                            rs.getString("CORRELATION_ID"),
                            rs.getString("CONSENT_PAYLOAD"),
                            rs.getInt("ATTEMPT_COUNT")
                    ),
                    operationId,
                    claimToken
            );

            return Optional.ofNullable(delivery);
        }

        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApplied(Delivery delivery) {

        int updated = jdbc.update("""
                UPDATE CF_CONSENT_DELIVERIES
                SET DELIVERY_STATUS = 'APPLIED',
                    APPLIED_AT = SYSTIMESTAMP,
                    LAST_HTTP_STATUS = 200,
                    LAST_ERROR = NULL,
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL,
                    UPDATED_AT = SYSTIMESTAMP
                WHERE OPERATION_ID = ?
                  AND DELIVERY_STATUS = 'PROCESSING'
                  AND CLAIM_TOKEN = ?
                """,
                delivery.operationId(),
                delivery.claimToken()
        );

        if (updated != 1) {
            // A different worker has reclaimed this operation.
            return;
        }

        int consentUpdated = jdbc.update("""
                UPDATE CF_CONSENTS
                SET ORCHESTRATION_APPLIED_VERSION = ?,
                    ROW_VERSION = ROW_VERSION + 1,
                    UPDATED_AT = SYSTIMESTAMP
                WHERE CONSENT_ID = ?
                  AND CONSENT_VERSION >= ?
                  AND ORCHESTRATION_APPLIED_VERSION < ?
                """,
                delivery.consentVersion(),
                delivery.consentId(),
                delivery.consentVersion(),
                delivery.consentVersion()
        );

        if (consentUpdated == 0) {
            Integer appliedVersion = jdbc.queryForObject("""
                    SELECT ORCHESTRATION_APPLIED_VERSION
                    FROM CF_CONSENTS
                    WHERE CONSENT_ID = ?
                    """,
                    Integer.class,
                    delivery.consentId()
            );

            if (appliedVersion == null
                    || appliedVersion < delivery.consentVersion()) {
                throw new IllegalStateException(
                        "CONSENT_APPLIED_VERSION_UPDATE_FAILED"
                );
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            Delivery delivery,
            Integer httpStatus,
            String errorCode) {

        boolean exhausted = delivery.attemptCount() >= 20;

        int delaySeconds = (int) Math.min(
                300L,
                5L * (1L << Math.min(
                        delivery.attemptCount() - 1,
                        6
                ))
        );

        jdbc.update("""
                UPDATE CF_CONSENT_DELIVERIES
                SET DELIVERY_STATUS = ?,
                    NEXT_ATTEMPT_AT =
                        SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND'),
                    LAST_HTTP_STATUS = ?,
                    LAST_ERROR = ?,
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL,
                    UPDATED_AT = SYSTIMESTAMP
                WHERE OPERATION_ID = ?
                  AND DELIVERY_STATUS = 'PROCESSING'
                  AND CLAIM_TOKEN = ?
                """,
                exhausted ? "DEAD" : "PENDING",
                delaySeconds,
                httpStatus,
                errorCode,
                delivery.operationId(),
                delivery.claimToken()
        );
    }
}