package com.credflow.service;

import com.credflow.entity.*;
import com.credflow.platform.Platform;
import jakarta.persistence.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfferDocumentsTest {
	final EntityManager em = mock(EntityManager.class);
	final Platform p = mock(Platform.class);
	final OfferDocuments service = new OfferDocuments(em, p);
	final Jwt jwt = Jwt.withTokenValue("unit-test").header("alg", "none").subject("seller").build();

	byte[] pdf() {
		return "%PDF-1.7\nterms\n%%EOF".getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void rejectsInvalidEmptyOversizedAndMisnamedFiles() {
		for (byte[] bytes : new byte[][] { null, new byte[0], new byte[OfferDocuments.MAX_BYTES + 1],
				"plain text".getBytes() })
			assertThrows(ResponseStatusException.class, () -> OfferDocuments.validatePdf("terms.pdf", bytes));
		assertThrows(ResponseStatusException.class, () -> OfferDocuments.validatePdf("terms.txt", pdf()));
		assertThrows(ResponseStatusException.class,
				() -> OfferDocuments.validatePdf("terms.pdf", "%PDF-1.7 incomplete".getBytes()));
		assertEquals("terms.pdf", OfferDocuments.validatePdf("C:\\private\\terms.pdf", pdf()));
	}

	OfferRow offer() {
		var o = new OfferRow();
		o.setId(UUID.randomUUID());
		o.setApplicationId(UUID.randomUUID());
		o.setSubmissionId(UUID.randomUUID());
		o.setTermsReference("TERMS-1");
		o.setStatus("ACTIVE");
		when(em.find(OfferRow.class, o.getId())).thenReturn(o);
		var w = new WorkflowRow();
		w.setSellerSubject("seller");
		w.setSellerId("S-1");
		w.setLenderId("bank");
		when(em.find(WorkflowRow.class, o.getApplicationId())).thenReturn(w);
		when(p.hasRole(jwt, "SELLER")).thenReturn(true);
		return o;
	}

	@Test
	void missingDocumentPreventsAcceptanceButOldAcceptedReplayIsPreserved() {
		var o = offer();
		TypedQuery<Object[]> query = mock(TypedQuery.class);
		when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
		when(query.setParameter(anyString(), any())).thenReturn(query);
		when(query.getResultList()).thenReturn(List.of());
		assertThrows(ResponseStatusException.class, () -> service.requireForAcceptance(o.getId(), jwt));
		o.setStatus("ACCEPTED");
		assertDoesNotThrow(() -> service.requireForAcceptance(o.getId(), jwt));
	}

	@Test
	void ownershipCheckedBeforeReadingDocument() {
		var o = offer();
		doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(p).owner(jwt, "seller",
				"S-1", "bank", null, true);
		assertThrows(ResponseStatusException.class, () -> service.read(o.getId(), jwt));
		verify(em, never()).find(OfferTermsRow.class, o.getId());
	}

	@Test
	void documentMustMatchTheOffer() {
		var o = offer();
		var doc = new OfferTermsRow();
		doc.setSubmissionId(o.getSubmissionId());
		doc.setTermsReference("DIFFERENT");
		when(em.find(OfferTermsRow.class, o.getId())).thenReturn(doc);
		assertThrows(ResponseStatusException.class, () -> service.read(o.getId(), jwt));
	}

	@Test
	void onlyLenderCanUpload() {
		assertThrows(ResponseStatusException.class,
				() -> service.upload(UUID.randomUUID(), UUID.randomUUID(), "TERMS", "terms.pdf", pdf(), jwt));
		verify(em, never()).persist(any());
	}
}
