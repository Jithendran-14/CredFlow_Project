package com.credflow.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationTimelineTest {
	@Test
	void readsPersistedOutboxTransitionsInChronologicalOrder() throws Exception {
		var em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
		var p = org.mockito.Mockito.mock(com.credflow.platform.Platform.class);
		var query = org.mockito.Mockito.mock(jakarta.persistence.TypedQuery.class);
		var id = java.util.UUID.randomUUID();
		var w = new com.credflow.entity.WorkflowRow();
		w.setId(id);
		w.setStatus("UNDER_REVIEW");
		org.mockito.Mockito.when(em.find(com.credflow.entity.WorkflowRow.class, id)).thenReturn(w);
		org.mockito.Mockito
				.when(em.createQuery(org.mockito.ArgumentMatchers.contains("d.kind='OUTBOX'"),
						org.mockito.ArgumentMatchers.eq(com.credflow.platform.entity.DeliveryRow.class)))
				.thenReturn(query);
		org.mockito.Mockito.when(query.setParameter("id", id)).thenReturn(query);
		org.mockito.Mockito.when(query.setMaxResults(501)).thenReturn(query);
		var first = new com.credflow.platform.entity.DeliveryRow();
		first.setId("OUTBOX:first");
		first.setCreatedAt(java.time.Instant.parse("2026-10-21T10:00:00Z"));
		first.setPayload("{\"eventId\":\"first\",\"data\":{\"status\":\"DRAFT\",\"workflowVersion\":1}}");
		var later = new com.credflow.platform.entity.DeliveryRow();
		later.setId("OUTBOX:later");
		later.setCreatedAt(java.time.Instant.parse("2026-10-21T11:00:00Z"));
		later.setPayload(
				"{\"eventId\":\"later\",\"data\":{\"status\":\"UNDER_REVIEW\",\"previousStatus\":\"SUBMITTING\",\"workflowVersion\":3}}");
		var health = new com.credflow.platform.entity.DeliveryRow();
		health.setId("OUTBOX:health");
		health.setCreatedAt(java.time.Instant.parse("2026-10-21T12:00:00Z"));
		health.setPayload("{\"data\":{\"status\":\"UNDER_REVIEW\",\"previousStatus\":\"UNDER_REVIEW\"}}");
		org.mockito.Mockito.when(query.getResultList()).thenReturn(java.util.List.of(health, later, first));
		var result = new ApplicationTimeline(em, p, new com.fasterxml.jackson.databind.ObjectMapper()).read(id, null);
		var items = (java.util.List<java.util.Map<String, Object>>) result.get("items");
		assertEquals(2, items.size());
		assertEquals("DRAFT", items.getFirst().get("status"));
		assertEquals("UNDER_REVIEW", items.getLast().get("status"));
		assertEquals("Lender", result.get("nextActor"));
	}

	@Test
	void identifiesNextActorAcrossWorkflow() {
		assertEquals("Seller", ApplicationTimeline.next("DRAFT", null, false).get("actor"));
		assertEquals("CredFlow", ApplicationTimeline.next("ELIGIBILITY_CHECKING", null, false).get("actor"));
		assertEquals("Lender", ApplicationTimeline.next("UNDER_REVIEW", null, false).get("actor"));
		assertEquals("Seller", ApplicationTimeline.next("APPROVED", null, false).get("actor"));
		assertEquals("Marketplace",
				ApplicationTimeline.next("ACTION_REQUIRED", "BUSINESS_DETAILS_REVIEW_REQUIRED", false).get("actor"));
		assertEquals("Seller",
				ApplicationTimeline.next("ACTION_REQUIRED", "FINANCIAL_CONSENT_REQUIRED", false).get("actor"));
	}

	@Test
	void acceptanceWaitsForActualLenderAcknowledgement() {
		assertEquals("Lender", ApplicationTimeline.next("OFFER_ACCEPTED", null, false).get("actor"));
		assertEquals("No further action", ApplicationTimeline.next("OFFER_ACCEPTED", null, true).get("actor"));
		assertEquals("No further action", ApplicationTimeline.next("CANCELLED", null, false).get("actor"));
	}
}
