package com.credflow.consent.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentOutboxStore {

    private final JdbcTemplate jdbc;

    public ConsentOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ClaimedEvent(
            String eventId,
            String applicationId,
            String eventType,
            String payload,
            String claimToken,
            int retryCount
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedEvent> claimNext() {

        var candidates = jdbc.queryForList("""
                SELECT current_event.EVENT_ID
                FROM CF_OUTBOX_EVENTS current_event
                WHERE (
                    (
                        current_event.EVENT_STATUS = 'PENDING'
                        AND current_event.NEXT_ATTEMPT_AT
                            <= SYSTIMESTAMP
                    )
                    OR (
                        current_event.EVENT_STATUS = 'PROCESSING'
                        AND current_event.LEASE_EXPIRES_AT
                            <= SYSTIMESTAMP
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM CF_OUTBOX_EVENTS older
                    WHERE older.APPLICATION_ID =
                          current_event.APPLICATION_ID
                      AND older.AGGREGATE_VERSION <
                          current_event.AGGREGATE_VERSION
                      AND older.EVENT_STATUS <> 'PUBLISHED'
                )
                ORDER BY
                    current_event.CREATED_AT,
                    current_event.EVENT_ID
                FETCH FIRST 10 ROWS ONLY
                """, String.class);

        for (String eventId : candidates) {
            String claimToken = UUID.randomUUID().toString();

            int updated = jdbc.update("""
                    UPDATE CF_OUTBOX_EVENTS
                    SET EVENT_STATUS = 'PROCESSING',
                        CLAIM_TOKEN = ?,
                        LEASE_EXPIRES_AT =
                            SYSTIMESTAMP
                            + NUMTODSINTERVAL(180, 'SECOND')
                    WHERE EVENT_ID = ?
                      AND (
                          (
                              EVENT_STATUS = 'PENDING'
                              AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                          )
                          OR (
                              EVENT_STATUS = 'PROCESSING'
                              AND LEASE_EXPIRES_AT <= SYSTIMESTAMP
                          )
                      )
                    """,
                    claimToken,
                    eventId
            );

            if (updated != 1) {
                continue;
            }

            ClaimedEvent event = jdbc.queryForObject("""
                    SELECT
                        EVENT_ID,
                        APPLICATION_ID,
                        EVENT_TYPE,
                        EVENT_PAYLOAD,
                        CLAIM_TOKEN,
                        RETRY_COUNT
                    FROM CF_OUTBOX_EVENTS
                    WHERE EVENT_ID = ?
                      AND CLAIM_TOKEN = ?
                    """,
                    (rs, rowNumber) -> new ClaimedEvent(
                            rs.getString("EVENT_ID"),
                            rs.getString("APPLICATION_ID"),
                            rs.getString("EVENT_TYPE"),
                            rs.getString("EVENT_PAYLOAD"),
                            rs.getString("CLAIM_TOKEN"),
                            rs.getInt("RETRY_COUNT")
                    ),
                    eventId,
                    claimToken
            );

            return Optional.ofNullable(event);
        }

        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(ClaimedEvent event) {

        int updated = jdbc.update("""
                UPDATE CF_OUTBOX_EVENTS
                SET EVENT_STATUS = 'PUBLISHED',
                    PUBLISHED_AT = SYSTIMESTAMP,
                    LAST_ERROR = NULL,
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL
                WHERE EVENT_ID = ?
                  AND EVENT_STATUS = 'PROCESSING'
                  AND CLAIM_TOKEN = ?
                """,
                event.eventId(),
                event.claimToken()
        );

        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            ClaimedEvent event,
            String errorCode,
            boolean permanent) {

        int nextRetryCount = event.retryCount() + 1;

        boolean dead = permanent || nextRetryCount >= 20;

        int delaySeconds = (int) Math.min(
                300L,
                5L * (1L << Math.min(event.retryCount(), 6))
        );

        jdbc.update("""
                UPDATE CF_OUTBOX_EVENTS
                SET EVENT_STATUS = ?,
                    RETRY_COUNT = RETRY_COUNT + 1,
                    NEXT_ATTEMPT_AT =
                        SYSTIMESTAMP
                        + NUMTODSINTERVAL(?, 'SECOND'),
                    LAST_ERROR = ?,
                    CLAIM_TOKEN = NULL,
                    LEASE_EXPIRES_AT = NULL
                WHERE EVENT_ID = ?
                  AND EVENT_STATUS = 'PROCESSING'
                  AND CLAIM_TOKEN = ?
                """,
                dead ? "DEAD" : "PENDING",
                delaySeconds,
                errorCode,
                event.eventId(),
                event.claimToken()
        );
    }
}