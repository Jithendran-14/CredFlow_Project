package com.credflow.consent.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.credflow.consent.dto.VerificationRequest;

import tools.jackson.databind.ObjectMapper;

@Component
public class VerificationResultEventBuilder {

	private static final Set<String> ALLOWED_STATUSES = Set.of("VERIFIED", "FAILED", "PENDING", "ACTION_REQUIRED");

	private final ObjectMapper objectMapper;

	public VerificationResultEventBuilder(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public record ResultData(String status, String reasonCode, OffsetDateTime checkedAt, String fixtureIdentityId,
			String providerReference, String authorisedRetrievalReference) {
	}

	public String build(VerificationRequest request, ResultData result, UUID eventId, long aggregateVersion,
			OffsetDateTime occurredAt) {

		if (result.status() == null || !ALLOWED_STATUSES.contains(result.status()) || aggregateVersion < 1
				|| result.checkedAt() == null) {

			throw new IllegalArgumentException("INVALID_VERIFICATION_EVENT_DATA");
		}
		if (("FAILED".equals(result.status()) || "ACTION_REQUIRED".equals(result.status()))
				&& !hasText(result.reasonCode())) {

			throw new IllegalArgumentException("VERIFICATION_REASON_REQUIRED");
		}

		if ("VERIFIED".equals(result.status())
				&& (!hasText(result.fixtureIdentityId()) || !hasText(result.authorisedRetrievalReference()))) {

			throw new IllegalArgumentException("VERIFIED_RESULT_REQUIRES_IDENTITY_AND_AUTHORIZATION");
		}

		if (!"VERIFIED".equals(result.status()) && result.authorisedRetrievalReference() != null) {

			throw new IllegalArgumentException("RETRIEVAL_REFERENCE_REQUIRES_VERIFIED_RESULT");
		}

		Map<String, Object> data = new LinkedHashMap<>();

		data.put("verificationId", request.verificationId().toString());
		data.put("applicationRevision", request.applicationRevision());
		data.put("status", result.status());
		data.put("checkedAt", result.checkedAt().toString());
		if (hasText(result.fixtureIdentityId())) {
			data.put("fixtureIdentityId", result.fixtureIdentityId());
		}
		if (hasText(result.reasonCode())) {
			data.put("reasonCode", result.reasonCode());
		}

		if (hasText(result.providerReference())) {
			data.put("providerReference", result.providerReference());
		}

		if (hasText(result.authorisedRetrievalReference())) {
			data.put("authorisedRetrievalReference", result.authorisedRetrievalReference());
		}

		Map<String, Object> originActor = new LinkedHashMap<>();

		originActor.put("type", request.originActor().type());
		originActor.put("subject", request.originActor().subject());

		if (request.originActor().organisationId() != null) {
			originActor.put("organisationId", request.originActor().organisationId().toString());
		}

		Map<String, Object> event = new LinkedHashMap<>();

		event.put("eventId", eventId.toString());
		event.put("eventType", "verification.completed");
		event.put("schemaVersion", "1.0");
		event.put("occurredAt", occurredAt.toString());
		event.put("producer", "consent-verification-service");
		event.put("applicationId", request.applicationId().toString());
		event.put("aggregateVersion", aggregateVersion);
		event.put("correlationId", request.correlationId());

		if (request.causationId() != null) {
			event.put("causationId", request.causationId().toString());
		}

		event.put("data", data);

		event.put("actor", Map.of("type", "SYSTEM_CLIENT", "subject", "consent-verification-service"));

		event.put("originActor", originActor);

		return objectMapper.writeValueAsString(event);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}