package com.credflow.consent.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class FinancialConsentExpiryScheduler {
	private static final Logger log = LoggerFactory.getLogger(FinancialConsentExpiryScheduler.class);
	private final JdbcTemplate jdbc;
	private final FinancialConsentEvents events;

	public FinancialConsentExpiryScheduler(JdbcTemplate jdbc, FinancialConsentEvents events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	@Scheduled(fixedDelayString = "${credflow.financial-consent.expiry-delay-ms:5000}")
	public void tick() {
		for (String id : jdbc.queryForList(
				"""
						SELECT c.CONSENT_ID FROM CF_FINANCIAL_CONSENTS c WHERE
						NOT EXISTS (SELECT 1 FROM CF_OUTBOX_EVENTS e WHERE e.CAUSATION_ID=c.CONSENT_ID AND e.EVENT_TYPE='financial.consent.granted')
						OR (c.CONSENT_STATUS='REVOKED' AND NOT EXISTS (SELECT 1 FROM CF_OUTBOX_EVENTS e WHERE e.CAUSATION_ID=c.CONSENT_ID AND e.EVENT_TYPE='financial.consent.withdrawn'))
						ORDER BY c.GRANTED_AT FETCH FIRST 50 ROWS ONLY
						""",
				String.class)) {
			try {
				events.reconcileHistory(UUID.fromString(id));
			} catch (RuntimeException ex) {
				log.warn("Financial consent history reconciliation pending consentId={} errorType={}", id,
						ex.getClass().getSimpleName());
			}
		}
		for (String id : jdbc.queryForList(
				"""
						SELECT c.CONSENT_ID FROM CF_FINANCIAL_CONSENTS c
						WHERE c.CONSENT_STATUS='ACTIVE' AND c.EXPIRES_AT<=SYSTIMESTAMP
						AND NOT EXISTS (SELECT 1 FROM CF_OUTBOX_EVENTS e WHERE e.CAUSATION_ID=c.CONSENT_ID AND e.EVENT_TYPE='financial.consent.expired')
						ORDER BY c.EXPIRES_AT FETCH FIRST 50 ROWS ONLY
						""",
				String.class)) {
			try {
				events.expire(UUID.fromString(id));
			} catch (RuntimeException ex) {
				log.warn("Financial consent expiry pending consentId={} errorType={}", id,
						ex.getClass().getSimpleName());
			}
		}
	}
}
