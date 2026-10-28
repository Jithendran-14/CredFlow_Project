package com.credflow.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

final class AuditTestMessages {
	static ObjectNode event(ObjectMapper json) throws Exception {
		return (ObjectNode) json.readTree(
				"""
						{"eventId":"22222222-2222-4222-8222-222222222222","applicationId":"33333333-3333-4333-8333-333333333333",
						 "eventType":"application.submitted","schemaVersion":"1.0","producer":"application-service",
						 "occurredAt":"2026-10-15T09:30:00Z","aggregateVersion":1,"correlationId":"demo-audit-104",
						 "actor":{"type":"SYSTEM_CLIENT","subject":"mock-marketplace-client","organisationId":"11111111-1111-4111-8111-111111111111"},
						 "data":{"sellerId":"44444444-4444-4444-8444-444444444444","marketplaceId":"11111111-1111-4111-8111-111111111111",
						 "requestedAmount":"500000.00","currency":"INR","status":"DRAFT","applicationRevision":1,"sellerRequestReference":"request-104"}}
						""");
	}

	static String unique(ObjectMapper json) throws Exception {
		var e = event(json);
		e.put("eventId", UUID.randomUUID().toString());
		e.put("applicationId", UUID.randomUUID().toString());
		return json.writeValueAsString(e);
	}
}
