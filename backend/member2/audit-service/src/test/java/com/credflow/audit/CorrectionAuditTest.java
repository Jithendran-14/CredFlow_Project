package com.credflow.audit;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrectionAuditTest {
	@Test
	void recordsCorrectionWithoutCopyingBusinessProfile() throws Exception {
		var json = new ObjectMapper();
		var data = new LinkedHashMap<String, Object>();
		data.put("operationId", UUID.randomUUID().toString());
		data.put("applicationRevision", 2);
		data.put("previousRevision", 1);
		data.put("requestedAmount", "180000.00");
		data.put("currency", "INR");
		data.put("loanPurpose", "WORKING_CAPITAL");
		data.put("businessProfile", Map.of("displayName", "Private Seller"));
		var event = Map.of("eventId", UUID.randomUUID().toString(), "eventType", "application.revised", "schemaVersion",
				"1.0", "occurredAt", "2026-10-21T10:00:00Z", "producer", "application-service", "applicationId",
				UUID.randomUUID().toString(), "aggregateVersion", 2, "correlationId", UUID.randomUUID().toString(),
				"actor", Map.of("type", "HUMAN", "subject", "seller"), "data", data);
		var projected = new AuditEventProjector(json).project(json.writeValueAsString(event));
		assertEquals(2, projected.path("data").path("applicationRevision").asInt());
		assertFalse(projected.path("data").has("businessProfile"));
	}
}
