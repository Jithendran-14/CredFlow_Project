package com.credflow.consent.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "credflow.verification-failures.enabled",
        havingValue = "true"
)
public class VerificationFailureWorker {

    private static final Logger log =
            LoggerFactory.getLogger(
                    VerificationFailureWorker.class
            );

    private final JdbcTemplate jdbc;
    private final VerificationFailureCompletion completion;

    public VerificationFailureWorker(
            JdbcTemplate jdbc,
            VerificationFailureCompletion completion) {

        this.jdbc = jdbc;
        this.completion = completion;
    }

    @Scheduled(
        fixedDelayString =
            "${credflow.verification-failures.poll-delay-ms:5000}"
    )
    public void poll() {

        try {
            var ids = jdbc.queryForList("""
                    SELECT r.VERIFICATION_ID
                    FROM CF_VERIFICATION_REQUESTS r
                    LEFT JOIN CF_IDENTITY_CHECKS i
                      ON i.VERIFICATION_ID = r.VERIFICATION_ID
                    WHERE r.PROCESSING_STATUS IN ('PENDING', 'DEAD')
                      AND r.NEXT_ATTEMPT_AT <= SYSTIMESTAMP
                      AND (
                          r.PRECHECK_STATUS = 'REJECTED'
                          OR r.PROCESSING_STATUS = 'DEAD'
                          OR i.CHECK_STATUS = 'DEAD'
                      )
                    ORDER BY r.UPDATED_AT, r.VERIFICATION_ID
                    FETCH FIRST 10 ROWS ONLY
                    """,
                    String.class
            );

            for (String id : ids) {
                try {
                    completion.complete(UUID.fromString(id));

                } catch (Exception exception) {
                    log.warn(
                            "Verification failure completion failed: id={}",
                            id,
                            exception
                    );

                    jdbc.update("""
                            UPDATE CF_VERIFICATION_REQUESTS
                            SET NEXT_ATTEMPT_AT =
                                    SYSTIMESTAMP
                                    + NUMTODSINTERVAL(60, 'SECOND'),
                                LAST_FINALIZATION_ERROR =
                                    'FAILURE_COMPLETION_ERROR'
                            WHERE VERIFICATION_ID = ?
                              AND PROCESSING_STATUS <> 'COMPLETED'
                            """,
                            id
                    );
                }
            }

        } catch (Exception exception) {
            log.error(
                    "Verification failure poll failed",
                    exception
            );
        }
    }
}