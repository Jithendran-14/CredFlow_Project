package com.credflow.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class AuditEventProjectorTest {
	final ObjectMapper json = new ObjectMapper();
	final AuditEventProjector projector = new AuditEventProjector(json);

	@Test
	void keepsOnlyApprovedEnvelopeActorAndDataFields() throws Exception {
		var e = AuditTestMessages.event(json);
		e.put("password", "TOP_SECRET");
		((ObjectNode) e.path("actor")).put("accessToken", "TOP_SECRET");
		((ObjectNode) e.path("data")).putObject("rawIdentityDocument").put("value", "TOP_SECRET");
		var safe = projector.project(json.writeValueAsString(e));
		assertThat(safe.toString()).doesNotContain("TOP_SECRET", "password", "accessToken", "rawIdentityDocument");
		assertThat(safe.path("data").path("requestedAmount").asText()).isEqualTo("500000.00");
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(bad("UUID", e -> e.put("eventId", "bad")), bad("missing actor", e -> e.remove("actor")),
				bad("actor type", e -> ((ObjectNode) e.path("actor")).put("type", "ADMIN")),
				bad("actor subject", e -> ((ObjectNode) e.path("actor")).put("subject", 123)),
				bad("producer", e -> e.put("producer", "unknown-service")),
				bad("event type", e -> e.put("eventType", "eligibility.completed")),
				bad("schema version", e -> e.put("schemaVersion", "2.0")),
				bad("time", e -> e.put("occurredAt", "2026-10-15")), bad("version", e -> e.put("aggregateVersion", 0)),
				bad("correlation length", e -> e.put("correlationId", "x".repeat(101))),
				bad("missing data field", e -> ((ObjectNode) e.path("data")).remove("sellerId")),
				bad("numeric money", e -> ((ObjectNode) e.path("data")).put("requestedAmount", 500000)),
				bad("negative money", e -> ((ObjectNode) e.path("data")).put("requestedAmount", "-1.00")),
				bad("zero money", e -> ((ObjectNode) e.path("data")).put("requestedAmount", "0.00")),
				bad("currency", e -> ((ObjectNode) e.path("data")).put("currency", "USD")),
				bad("status", e -> ((ObjectNode) e.path("data")).put("status", "REJECTED")),
				bad("revision type", e -> ((ObjectNode) e.path("data")).put("applicationRevision", "1")),
				bad("origin actor", e -> e.putNull("originActor")),
				bad("causation", e -> e.put("causationId", "invalid")));
	}

	static Arguments bad(String name, Consumer<ObjectNode> change) {
		return Arguments.of(name, change);
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void refusesMalformedSupportedFields(String name, Consumer<ObjectNode> change) throws Exception {
		var e = AuditTestMessages.event(json);
		change.accept(e);
		assertThatThrownBy(() -> projector.project(json.writeValueAsString(e))).as(name)
				.isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{", "[]", "null", "{}" })
	void rejectsInvalidEnvelopes(String payload) {
		assertThatThrownBy(() -> projector.project(payload))
				.isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
	}

	@Test
	void normalizedTextKeepsFractionalSecondsInChronologicalOrder() throws Exception {
		var e = AuditTestMessages.event(json);
		e.put("occurredAt", "2026-10-15T09:30:00Z");
		String first = projector.project(json.writeValueAsString(e)).path("occurredAt").asText();
		e.put("occurredAt", "2026-10-15T09:30:00.1Z");
		String later = projector.project(json.writeValueAsString(e)).path("occurredAt").asText();
		assertThat(first.compareTo(later)).isNegative();
	}

	@Test
	void normalizesTimestampsAndKeepsOriginAttribution() throws Exception {
		var e = AuditTestMessages.event(json);
		e.put("occurredAt", "2026-10-15T15:00:00+05:30");
		e.set("originActor", e.path("actor").deepCopy());
		e.put("causationId", "55555555-5555-4555-8555-555555555555");
		var safe = projector.project(json.writeValueAsString(e));
		assertThat(safe.path("occurredAt").asText()).isEqualTo("2026-10-15T09:30:00.000000000Z");
		assertThat(safe.path("originActor").path("subject").asText()).isEqualTo("mock-marketplace-client");
		assertThat(safe.path("causationId").asText()).isEqualTo("55555555-5555-4555-8555-555555555555");
	}
}
