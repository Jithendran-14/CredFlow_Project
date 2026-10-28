package com.credflow.consent.service;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Lifecycle history is committed atomically with the grant or withdrawal. */
@Service
public class FinancialConsentEvents {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public FinancialConsentEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	public Map<String, Object> current(UUID applicationId, int revision) {
		var rows = jdbc.query("""
				SELECT CONSENT_ID, CONSENT_VERSION, CONSENT_STATUS, EXPIRES_AT, OPERATIONAL_CONSENT_VERSION
				FROM CF_FINANCIAL_CONSENTS WHERE APPLICATION_ID=? AND APPLICATION_REVISION=?
				ORDER BY CONSENT_VERSION DESC FETCH FIRST 1 ROW ONLY
				""", (rs, n) -> {
			var expiry = rs.getObject(4, OffsetDateTime.class);
			String status = rs.getString(3);
			if ("ACTIVE".equals(status) && !expiry.isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
				status = "EXPIRED";
			return Map.<String, Object>of("financialConsentId", rs.getString(1), "financialConsentVersion",
					rs.getInt(2), "status", status, "expiresAt", expiry.toString(), "operationalConsentVersion",
					rs.getInt(5));
		}, applicationId.toString(), revision);
		var result = new LinkedHashMap<String, Object>(
				rows.isEmpty() ? Map.of("status", "NOT_RECORDED") : rows.getFirst());
		result.put("applicationId", applicationId.toString());
		result.put("applicationRevision", revision);
		return result;
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void append(UUID consentId, String action, OffsetDateTime at, String subject, String correlation) {
		if (!Set.of("granted", "withdrawn", "expired").contains(action))
			throw new IllegalArgumentException("INVALID_FINANCIAL_CONSENT_ACTION");
		var row = jdbc.queryForMap(
				"SELECT APPLICATION_ID, APPLICATION_REVISION, CONSENT_VERSION, OPERATIONAL_CONSENT_VERSION, EXPIRES_AT FROM CF_FINANCIAL_CONSENTS WHERE CONSENT_ID=?",
				consentId.toString());
		String applicationId = (String) row.get("APPLICATION_ID");
		jdbc.update(
				"MERGE INTO CF_CONSENT_EVENT_COUNTERS t USING (SELECT ? APPLICATION_ID FROM DUAL) s ON (t.APPLICATION_ID=s.APPLICATION_ID) WHEN NOT MATCHED THEN INSERT (APPLICATION_ID,LAST_EVENT_VERSION) VALUES (s.APPLICATION_ID,0)",
				applicationId);
		Long previous = jdbc.queryForObject(
				"SELECT LAST_EVENT_VERSION FROM CF_CONSENT_EVENT_COUNTERS WHERE APPLICATION_ID=? FOR UPDATE",
				Long.class, applicationId);
		long version = Math.addExact(Objects.requireNonNull(previous), 1);
		jdbc.update("UPDATE CF_CONSENT_EVENT_COUNTERS SET LAST_EVENT_VERSION=? WHERE APPLICATION_ID=?", version,
				applicationId);
		UUID eventId = UUID.randomUUID();
		String type = "financial.consent." + action;
		var data = new LinkedHashMap<String, Object>();
		data.put("financialConsentId", consentId.toString());
		data.put("financialConsentVersion", row.get("CONSENT_VERSION"));
		data.put("applicationRevision", row.get("APPLICATION_REVISION"));
		data.put("operationalConsentVersion", row.get("OPERATIONAL_CONSENT_VERSION"));
		data.put("status", switch (action) {
		case "granted" -> "ACTIVE";
		case "withdrawn" -> "REVOKED";
		default -> "EXPIRED";
		});
		var actor = Map.of("type", action.equals("expired") ? "SYSTEM_CLIENT" : "HUMAN", "subject", subject);
		var envelope = new LinkedHashMap<String, Object>();
		envelope.put("eventId", eventId.toString());
		envelope.put("eventType", type);
		envelope.put("schemaVersion", "1.0");
		envelope.put("occurredAt", at.toString());
		envelope.put("producer", "consent-verification-service");
		envelope.put("applicationId", applicationId);
		envelope.put("aggregateVersion", version);
		envelope.put("correlationId", correlation);
		envelope.put("causationId", consentId.toString());
		envelope.put("actor", actor);
		envelope.put("originActor", actor);
		envelope.put("data", data);
		jdbc.update(
				"""
						INSERT INTO CF_OUTBOX_EVENTS (EVENT_ID,APPLICATION_ID,AGGREGATE_VERSION,EVENT_TYPE,SCHEMA_VERSION,CORRELATION_ID,CAUSATION_ID,EVENT_PAYLOAD)
						VALUES (?,?,?,?,?,?,?,?)
						""",
				eventId.toString(), applicationId, version, type, "1.0", correlation, consentId.toString(),
				new SqlParameterValue(Types.CLOB, mapper.writeValueAsString(envelope)));
	}

	@Transactional
	public void expire(UUID id) {
		var rows = jdbc.queryForList(
				"SELECT APPLICATION_ID, EXPIRES_AT FROM CF_FINANCIAL_CONSENTS WHERE CONSENT_ID=? AND CONSENT_STATUS='ACTIVE' AND EXPIRES_AT<=SYSTIMESTAMP FOR UPDATE",
				id.toString());
		if (rows.isEmpty() || !jdbc.queryForList(
				"SELECT EVENT_ID FROM CF_OUTBOX_EVENTS WHERE CAUSATION_ID=? AND EVENT_TYPE='financial.consent.expired'",
				id.toString()).isEmpty())
			return;
		jdbc.update(
				"UPDATE CF_RETRIEVAL_AUTHORIZATIONS SET AUTHORISATION_STATUS='REVOKED',REVOKED_AT=SYSTIMESTAMP,REVOCATION_REASON='FINANCIAL_CONSENT_EXPIRED' WHERE FINANCIAL_CONSENT_ID=? AND AUTHORISATION_STATUS='ACTIVE'",
				id.toString());
		OffsetDateTime expiry = jdbc.queryForObject("SELECT EXPIRES_AT FROM CF_FINANCIAL_CONSENTS WHERE CONSENT_ID=?",
				(rs, n) -> rs.getObject(1, OffsetDateTime.class), id.toString());
		append(id, "expired", expiry, "consent-verification-service", id.toString());
	}

	/**
	 * Repair missing historical lifecycle events using the original actor and
	 * timestamps.
	 */
	@Transactional
	public void reconcileHistory(UUID id) {
		var rows = jdbc
				.query("""
						SELECT CONSENT_STATUS,GRANTED_AT,REVOKED_AT,ACTOR_SUBJECT,CORRELATION_ID
						FROM CF_FINANCIAL_CONSENTS WHERE CONSENT_ID=? FOR UPDATE
						""",
						(rs, n) -> new History(rs.getString(1), rs.getObject(2, OffsetDateTime.class),
								rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getString(5)),
						id.toString());
		if (rows.isEmpty())
			return;
		var row = rows.getFirst();
		if (!recorded(id, "granted"))
			append(id, "granted", row.granted(), row.subject(), row.correlation());
		if ("REVOKED".equals(row.status()) && row.revoked() != null && !recorded(id, "withdrawn")) {
			var actions = jdbc.query(
					"SELECT ACTOR_SUBJECT,CORRELATION_ID FROM CF_FINANCIAL_CONSENT_ACTIONS WHERE FINANCIAL_CONSENT_ID=? ORDER BY CREATED_AT DESC FETCH FIRST 1 ROW ONLY",
					(rs, n) -> List.of(rs.getString(1), rs.getString(2)), id.toString());
			append(id, "withdrawn", row.revoked(), actions.isEmpty() ? row.subject() : actions.getFirst().get(0),
					actions.isEmpty() ? row.correlation() : actions.getFirst().get(1));
		}
	}

	private record History(String status, OffsetDateTime granted, OffsetDateTime revoked, String subject,
			String correlation) {
	}

	private boolean recorded(UUID id, String action) {
		return !jdbc.queryForList("SELECT EVENT_ID FROM CF_OUTBOX_EVENTS WHERE CAUSATION_ID=? AND EVENT_TYPE=?",
				id.toString(), "financial.consent." + action).isEmpty();
	}
}
