package com.credflow.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Explicit safe projections for the team's canonical schema 1.0 event envelope.
 */
public final class AuditEventProjector {
	public static class InvalidAuditEventException extends IllegalArgumentException {
		public InvalidAuditEventException(String message) {
			super(message);
		}
	}

	private final ObjectMapper json;
	private final WorkflowAuditProjection workflow;

	public AuditEventProjector(ObjectMapper json) {
		this.json = json;
		this.workflow = new WorkflowAuditProjection(json);
	}

	public ObjectNode project(String payload) {
		final JsonNode event;
		try {
			event = json.readTree(Objects.requireNonNull(payload));
		} catch (Exception ex) {
			throw invalid("Audit message must be valid JSON");
		}
		if (event == null || !event.isObject())
			throw invalid("Audit envelope must be an object");
		var safe = json.createObjectNode();
		safe.put("eventId", uuid(event, "eventId"));
		safe.put("applicationId", uuid(event, "applicationId"));
		String eventType = text(event, "eventType", 100);
		boolean legacyApplication = eventType.equals("application.submitted");
		String producer = legacyApplication ? "application-service" : workflow.producer(eventType);
		requireValue(event, "schemaVersion", "1.0");
		requireValue(event, "producer", producer);
		safe.put("eventType", eventType);
		safe.put("schemaVersion", "1.0");
		safe.put("producer", producer);
		safe.put("correlationId", text(event, "correlationId", 100));
		String occurred = text(event, "occurredAt", 100);
		try {
			safe.put("occurredAt", new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter()
					.format(OffsetDateTime.parse(occurred).toInstant()));
		} catch (java.time.DateTimeException ex) {
			throw invalid("occurredAt must include a valid timestamp and offset");
		}
		var version = event.path("aggregateVersion");
		if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 1)
			throw invalid("aggregateVersion must be a positive integer");
		safe.put("aggregateVersion", version.longValue());
		safe.set("actor", actor(event.path("actor")));
		if (event.has("originActor"))
			safe.set("originActor", actor(event.path("originActor")));
		if (event.has("causationId"))
			safe.put("causationId", uuid(event, "causationId"));
		JsonNode data = event.path("data");
		if (!data.isObject())
			throw invalid("data must be an object");
		if (!legacyApplication) {
			safe.set("data", workflow.data(eventType, data, safe.path("applicationId").asText()));
			return safe;
		}
		var approved = safe.putObject("data");
		approved.put("sellerId", text(data, "sellerId", 200));
		approved.put("marketplaceId", uuid(data, "marketplaceId"));
		String amount = text(data, "requestedAmount", 20);
		if (!amount.matches("[0-9]{1,17}\\.[0-9]{2}") || new BigDecimal(amount).signum() <= 0)
			throw invalid("requestedAmount must be a positive two-decimal string");
		approved.put("requestedAmount", amount);
		requireValue(data, "currency", "INR");
		approved.put("currency", "INR");
		requireValue(data, "status", "DRAFT");
		approved.put("status", "DRAFT");
		var revision = data.path("applicationRevision");
		if (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue() < 1)
			throw invalid("applicationRevision must be a positive integer");
		approved.put("applicationRevision", revision.intValue());
		approved.put("sellerRequestReference", text(data, "sellerRequestReference", 100));
		return safe;
	}

	private ObjectNode actor(JsonNode actor) {
		if (!actor.isObject())
			throw invalid("Actor must be an object");
		String type = text(actor, "type", 20);
		if (!Set.of("HUMAN", "SYSTEM_CLIENT", "SYSTEM").contains(type))
			throw invalid("Unknown actor type");
		var safe = json.createObjectNode();
		safe.put("type", type);
		safe.put("subject", text(actor, "subject", 1000));
		if (actor.has("organisationId"))
			safe.put("organisationId", uuid(actor, "organisationId"));
		return safe;
	}

	private String text(JsonNode object, String key, int max) {
		var value = object.path(key);
		if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
			throw invalid(key + " must be a nonblank string within its length limit");
		return value.textValue();
	}

	private String uuid(JsonNode object, String key) {
		String value = text(object, key, 36);
		if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
			throw invalid(key + " must be a UUID");
		return UUID.fromString(value).toString();
	}

	private void requireValue(JsonNode object, String key, String expected) {
		if (!expected.equals(text(object, key, 100)))
			throw invalid("Unsupported " + key);
	}

	private InvalidAuditEventException invalid(String message) {
		return new InvalidAuditEventException(message);
	}
}
