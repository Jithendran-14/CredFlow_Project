package com.credflow.consent.service;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.service.VerificationResultEventBuilder.ResultData;

@Component
public class VerificationEventOutbox {

	private final JdbcTemplate jdbc;
	private final VerificationResultEventBuilder builder;

	public VerificationEventOutbox(JdbcTemplate jdbc, VerificationResultEventBuilder builder) {

		this.jdbc = jdbc;
		this.builder = builder;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(VerificationRequest request, ResultData result) {

		String applicationId = request.applicationId().toString();

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
				""", applicationId);

		Long previous = jdbc.queryForObject("""
				SELECT LAST_EVENT_VERSION
				FROM CF_CONSENT_EVENT_COUNTERS
				WHERE APPLICATION_ID = ?
				FOR UPDATE
				""", Long.class, applicationId);

		if (previous == null) {
			throw new IllegalStateException("CONSENT_EVENT_COUNTER_MISSING");
		}

		long next = Math.addExact(previous, 1L);

		jdbc.update("""
				UPDATE CF_CONSENT_EVENT_COUNTERS
				SET LAST_EVENT_VERSION = ?
				WHERE APPLICATION_ID = ?
				""", next, applicationId);

		UUID eventId = UUID.randomUUID();

		String json = builder.build(request, result, eventId, next, OffsetDateTime.now(ZoneOffset.UTC));

		jdbc.update("""
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
				""", eventId.toString(), request.verificationId().toString(), applicationId, next,
				"verification.completed", "1.0", request.correlationId(),
				request.causationId() == null ? null : request.causationId().toString(),
				new SqlParameterValue(Types.CLOB, json));
	}
}