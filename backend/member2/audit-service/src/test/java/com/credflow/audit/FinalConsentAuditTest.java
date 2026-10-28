package com.credflow.audit;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FinalConsentAuditTest {
	@Test
	void recordsVerificationButDoesNotExposeRetrievalCapability() {
		var j = new ObjectMapper();
		var e = j.createObjectNode();
		e.put("eventId", UUID.randomUUID().toString()).put("eventType", "verification.completed")
				.put("applicationId", UUID.randomUUID().toString()).put("schemaVersion", "1.0")
				.put("producer", "consent-verification-service").put("aggregateVersion", 1)
				.put("correlationId", "final-consent-test").put("occurredAt", "2026-10-19T09:00:00Z");
		e.putObject("actor").put("type", "SYSTEM_CLIENT").put("subject", "consent-verification-service");
		e.putObject("data").put("verificationId", UUID.randomUUID().toString()).put("applicationRevision", 1)
				.put("status", "VERIFIED").put("checkedAt", "2026-10-19T09:00:00Z")
				.put("authorisedRetrievalReference", "private-capability");
		var safe = new AuditEventProjector(j).project(e.toString());
		assertThat(safe.path("data").path("status").asText()).isEqualTo("VERIFIED");
		assertThat(safe.toString()).doesNotContain("private-capability");
	}
}
