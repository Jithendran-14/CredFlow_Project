package com.credflow.service;

import com.credflow.entity.*;
import com.credflow.platform.Platform;
import jakarta.persistence.*;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class OfferDocuments {
	public static final int MAX_BYTES = 5 * 1024 * 1024;
	private final EntityManager em;
	private final Platform p;

	public OfferDocuments(EntityManager em, Platform p) {
		this.em = em;
		this.p = p;
	}

	private <T> T required(Class<T> type, UUID id) {
		var row = em.find(type, id);
		if (row == null)
			throw new ResponseStatusException(NOT_FOUND, "RESOURCE_NOT_FOUND");
		return row;
	}

	private OfferRow authorised(UUID id, Jwt jwt) {
		if (!p.hasRole(jwt, "SELLER") && !p.hasRole(jwt, "LENDER_OFFICER") && !p.hasRole(jwt, "ADMIN"))
			throw new ResponseStatusException(FORBIDDEN, "OFFER_ACCESS_REQUIRED");
		var offer = required(OfferRow.class, id);
		var w = required(WorkflowRow.class, offer.getApplicationId());
		p.owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), null, true);
		return offer;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> metadata(UUID id, Jwt jwt) {
		var offer = authorised(id, jwt);
		return metadata(id, offer.getSubmissionId(), offer.getTermsReference());
	}

	private Map<String, Object> metadata(UUID id, UUID submission, String reference) {
		var rows = em.createQuery(
				"select t.fileName,t.sha256,t.fileSize,t.uploadedAt,t.termsReference,t.submissionId from CfOfferTerms t where t.offerId=:id",
				Object[].class).setParameter("id", id).getResultList();
		if (rows.isEmpty())
			return Map.of("available", false);
		var row = rows.getFirst();
		if (!submission.equals(row[5]) || !reference.equals(row[4]))
			throw new ResponseStatusException(CONFLICT, "TERMS_DOCUMENT_OFFER_MISMATCH");
		return Map.of("available", true, "fileName", row[0], "sha256", row[1], "fileSize", row[2], "uploadedAt", row[3],
				"termsReference", row[4]);
	}

	@Transactional(readOnly = true)
	public OfferTermsRow read(UUID id, Jwt jwt) {
		var offer = authorised(id, jwt);
		var doc = required(OfferTermsRow.class, id);
		if (!offer.getSubmissionId().equals(doc.getSubmissionId())
				|| !offer.getTermsReference().equals(doc.getTermsReference()))
			throw new ResponseStatusException(CONFLICT, "TERMS_DOCUMENT_OFFER_MISMATCH");
		int size = doc.getContent().length;
		if (size != doc.getFileSize())
			throw new IllegalStateException("Stored PDF length mismatch");
		return doc;
	}

	@Transactional(readOnly = true)
	public void requireForAcceptance(UUID id, Jwt jwt) {
		var offer = authorised(id, jwt);
		if ("ACTIVE".equals(offer.getStatus()) && !Boolean.TRUE
				.equals(metadata(id, offer.getSubmissionId(), offer.getTermsReference()).get("available")))
			throw new ResponseStatusException(CONFLICT,
					"TERMS_PDF_REQUIRED: Ask your lender to attach the offer terms PDF before accepting.");
	}

	public Map<String, Object> upload(UUID submissionId, UUID offerId, String reference, String fileName, byte[] bytes,
			Jwt jwt) {
		if (!p.hasRole(jwt, "LENDER_OFFICER"))
			throw new ResponseStatusException(FORBIDDEN, "LENDER_OFFICER_REQUIRED");
		p.requireScope(jwt, "lender.review");
		var submission = required(SubmissionRow.class, submissionId);
		if (!submission.getLenderId().equals(p.lenderId(jwt)))
			throw new ResponseStatusException(NOT_FOUND, "RESOURCE_NOT_FOUND");
		var w = em.find(WorkflowRow.class, submission.getApplicationId(), LockModeType.PESSIMISTIC_WRITE);
		if (w == null)
			throw new ResponseStatusException(NOT_FOUND, "RESOURCE_NOT_FOUND");
		if (reference == null || reference.isBlank() || reference.length() > 255)
			throw new ResponseStatusException(BAD_REQUEST, "TERMS_REFERENCE_REQUIRED");
		reference = reference.trim();
		String safe = validatePdf(fileName, bytes), hash = hash(bytes);
		var prior = em.find(OfferTermsRow.class, offerId);
		if (prior != null) {
			if (!prior.getSubmissionId().equals(submissionId) || !prior.getTermsReference().equals(reference)
					|| !prior.getSha256().equals(hash))
				throw new ResponseStatusException(CONFLICT,
						"TERMS_DOCUMENT_IMMUTABLE: Create a new offer to change its terms PDF.");
			return metadata(offerId, submissionId, reference);
		}
		if (Set.of("CHECK_FAILED", "NOT_ELIGIBLE", "OFFER_ACCEPTED", "OFFER_DECLINED", "OFFER_EXPIRED", "REJECTED",
				"CANCELLED").contains(w.getStatus()))
			throw new ResponseStatusException(CONFLICT, "APPLICATION_CLOSED");
		var offer = em.find(OfferRow.class, offerId);
		if (offer != null && (!offer.getSubmissionId().equals(submissionId)
				|| !offer.getApplicationId().equals(submission.getApplicationId())
				|| !offer.getTermsReference().equals(reference) || !"ACTIVE".equals(offer.getStatus())))
			throw new ResponseStatusException(CONFLICT, "TERMS_DOCUMENT_OFFER_MISMATCH");
		var doc = new OfferTermsRow();
		doc.setOfferId(offerId);
		doc.setApplicationId(submission.getApplicationId());
		doc.setSubmissionId(submissionId);
		doc.setLenderId(submission.getLenderId());
		doc.setTermsReference(reference);
		doc.setFileName(safe);
		doc.setSha256(hash);
		doc.setFileSize(bytes.length);
		doc.setContent(bytes);
		doc.setUploadedBy(jwt.getSubject());
		doc.setUploadedAt(Instant.now());
		em.persist(doc);
		em.flush();
		return metadata(offerId, submissionId, reference);
	}

	public static String validatePdf(String name, byte[] bytes) {
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
			throw new ResponseStatusException(BAD_REQUEST, "Choose a PDF of at most 5 MB.");
		if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf") || bytes.length < 12
				|| !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
				|| !new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length),
						StandardCharsets.ISO_8859_1).contains("%%EOF"))
			throw new ResponseStatusException(BAD_REQUEST, "The selected file must be a PDF document.");
		String safe = name.replace('\\', '/');
		safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
		if (safe.length() > 140)
			safe = safe.substring(0, 136) + ".pdf";
		return safe.isBlank() ? "offer-terms.pdf" : safe;
	}

	private static String hash(byte[] b) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
