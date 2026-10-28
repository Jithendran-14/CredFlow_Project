package com.credflow.consent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.VerificationAcceptedResponse;
import com.credflow.consent.dto.VerificationRequest;

import tools.jackson.databind.ObjectMapper;

@Service
public class VerificationRequestService {

	private final VerificationAcceptanceTransactionService transactions;
	private final ObjectMapper objectMapper;

	public VerificationRequestService(VerificationAcceptanceTransactionService transactions,
			ObjectMapper objectMapper) {

		this.transactions = transactions;
		this.objectMapper = objectMapper;
	}

	public VerificationAcceptedResponse accept(VerificationRequest request, String idempotencyKey,
			String correlationHeader) {

		validateIdempotencyKey(idempotencyKey, request.verificationId());

		String correlationId = resolveCorrelation(correlationHeader, request.correlationId());

		VerificationRequest storedRequest = new VerificationRequest(request.verificationId(), request.applicationId(),
				request.applicationRevision(), request.consentId(), request.consentVersion(), request.originActor(),
				correlationId, request.causationId());

		String requestHash = fingerprint(request);

		try {
			return transactions.accept(storedRequest, requestHash);

		} catch (DataIntegrityViolationException failure) {

			// The failed insertion transaction has already rolled back.
			// A concurrent identical request may have inserted this ID.
			return transactions.findReplay(request.verificationId(), requestHash).orElseThrow(() -> failure);
		}
	}

	private void validateIdempotencyKey(String idempotencyKey, UUID verificationId) {

		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw badRequest("IDEMPOTENCY_KEY_REQUIRED");
		}

		UUID parsedKey;

		try {
			parsedKey = UUID.fromString(idempotencyKey);
		} catch (IllegalArgumentException exception) {
			throw badRequest("INVALID_IDEMPOTENCY_KEY");
		}

		if (!parsedKey.equals(verificationId)) {
			throw badRequest("IDEMPOTENCY_KEY_MUST_MATCH_VERIFICATION_ID");
		}
	}

	private String resolveCorrelation(String header, String body) {

		if (header != null && !header.matches("[A-Za-z0-9._:-]{1,100}")) {
			throw badRequest("INVALID_CORRELATION_ID");
		}

		if (header != null && body != null && !header.equals(body)) {
			throw badRequest("CORRELATION_ID_MISMATCH");
		}

		if (header != null) {
			return header;
		}

		if (body != null) {
			return body;
		}

		return UUID.randomUUID().toString();
	}

	private String fingerprint(VerificationRequest request) {

		Map<String, Object> actor = new TreeMap<>();
		actor.put("type", request.originActor().type());
		actor.put("subject", request.originActor().subject());

		if (request.originActor().organisationId() != null) {
			actor.put("organisationId", request.originActor().organisationId().toString());
		}

		Map<String, Object> business = new TreeMap<>();
		business.put("verificationId", request.verificationId().toString());
		business.put("applicationId", request.applicationId().toString());
		business.put("applicationRevision", request.applicationRevision());
		business.put("consentId", request.consentId().toString());
		business.put("consentVersion", request.consentVersion());
		business.put("originActor", actor);

		String canonicalJson = objectMapper.writeValueAsString(business);

		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));

			return HexFormat.of().formatHex(digest);

		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ResponseStatusException badRequest(String code) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
	}
}