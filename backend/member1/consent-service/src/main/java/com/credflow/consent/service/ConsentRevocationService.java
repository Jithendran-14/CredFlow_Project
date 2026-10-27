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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.dto.ConsentRevocationRequest;

import tools.jackson.databind.ObjectMapper;

@Service
public class ConsentRevocationService {

    private final ConsentRevocationTransactionService transactions;
    private final ObjectMapper objectMapper;

    public ConsentRevocationService(
            ConsentRevocationTransactionService transactions,
            ObjectMapper objectMapper) {

        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public ConsentDecisionResponse revoke(
            UUID applicationId,
            ConsentRevocationRequest request,
            String idempotencyKey,
            String correlationHeader,
            Jwt jwt) {

        if (jwt == null
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {

            throw new AccessDeniedException(
                    "Authenticated seller is required"
            );
        }

        String sellerId = jwt.getClaimAsString("sellerId");

        if (sellerId == null || sellerId.isBlank()) {
            throw new AccessDeniedException(
                    "Authenticated seller identity is required"
            );
        }

        UUID operationId = parseOperationId(idempotencyKey);
        String correlationId = resolveCorrelation(correlationHeader);

        Map<String, Object> business = new TreeMap<>();

        business.put("operation", "REVOKE_CONSENT");
        business.put("applicationId", applicationId.toString());
        business.put("sellerId", sellerId);
        business.put("actorSubject", jwt.getSubject());

        business.put(
                "expectedConsentVersion",
                request.expectedConsentVersion()
        );

        business.put("reasonCode", request.reasonCode());

        String requestHash = fingerprint(
                objectMapper.writeValueAsString(business)
        );

        try {
            return transactions.revoke(
                    applicationId,
                    sellerId,
                    jwt.getSubject(),
                    operationId,
                    correlationId,
                    requestHash,
                    request
            );

        } catch (DataIntegrityViolationException exception) {
            return transactions.resolveInsertConflict(
                    operationId,
                    requestHash,
                    exception
            );
        }
    }

    private UUID parseOperationId(String value) {

        if (value == null || value.isBlank()) {
            throw badRequest("IDEMPOTENCY_KEY_REQUIRED");
        }

        try {
            return UUID.fromString(value);

        } catch (IllegalArgumentException exception) {
            throw badRequest("IDEMPOTENCY_KEY_MUST_BE_UUID");
        }
    }

    private String resolveCorrelation(String value) {

        if (value == null) {
            return UUID.randomUUID().toString();
        }

        if (!value.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw badRequest("INVALID_CORRELATION_ID");
        }

        return value;
    }

    private String fingerprint(String json) {

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                code
        );
    }
}