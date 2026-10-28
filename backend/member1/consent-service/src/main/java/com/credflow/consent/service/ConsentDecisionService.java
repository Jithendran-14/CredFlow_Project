package com.credflow.consent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.client.ApplicationOwnershipClient;
import com.credflow.consent.dto.ConsentDecisionRequest;
import com.credflow.consent.dto.ConsentDecisionRequest.Decision;
import com.credflow.consent.dto.ConsentDecisionRequest.Scope;
import com.credflow.consent.dto.ConsentDecisionResponse;

import tools.jackson.databind.ObjectMapper;

@Service
public class ConsentDecisionService {

	private final ApplicationOwnershipClient applications;
	private final ConsentTransactionService transactions;
	private final ObjectMapper objectMapper;
	private final String noticeVersion;
	private final Set<UUID> allowedLenderIds;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private org.springframework.jdbc.core.JdbcTemplate lenderRegistry;

	private boolean validRecipientLenders(Set<UUID> ids) {
		for (UUID id : ids)
			if (!allowedLenderIds.contains(id)) {
				if (lenderRegistry == null || lenderRegistry
						.queryForList("SELECT LENDER_ID FROM CF_AUTH.CF_LENDER_REGISTRY WHERE LENDER_ID=?",
								String.class, id.toString())
						.isEmpty())
					return false;
			}
		return true;
	}

	public ConsentDecisionService(ApplicationOwnershipClient applications, ConsentTransactionService transactions,
			ObjectMapper objectMapper, @Value("${credflow.consent.notice-version}") String noticeVersion,
			@Value("${credflow.consent.allowed-lender-ids[0]}") UUID bankA,
			@Value("${credflow.consent.allowed-lender-ids[1]}") UUID nbfcB,
			@Value("${credflow.consent.allowed-lender-ids[2]:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3}") UUID bankC) {

		this.applications = applications;
		this.transactions = transactions;
		this.objectMapper = objectMapper;
		this.noticeVersion = noticeVersion;
		this.allowedLenderIds = Set.of(bankA, nbfcB, bankC);
	}

	public ConsentDecisionResponse record(UUID applicationId, ConsentDecisionRequest request, String idempotencyKey,
			String correlationHeader, Jwt jwt) {

		if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw new AccessDeniedException("Authenticated seller is required");
		}

		String sellerId = jwt.getClaimAsString("sellerId");

		applications.requireOwnership(applicationId, sellerId);

		var context = applications.getContext(applicationId);

		if (!sellerId.equals(context.sellerId())) {
			throw new AccessDeniedException("Application seller identity has changed");
		}

		UUID operationId = parseOperationId(idempotencyKey);
		String correlationId = resolveCorrelation(correlationHeader);

		validateDecision(request, context.marketplaceId());

		String hash = fingerprint(applicationId, sellerId, jwt.getSubject(), request);

		try {
			return transactions.recordInitialDecision(applicationId, sellerId, jwt.getSubject(), operationId,
					correlationId, hash, request);

		} catch (DataIntegrityViolationException failure) {
			return transactions.resolveInsertConflict(applicationId, operationId, hash, failure);
		}
	}

	private void validateDecision(ConsentDecisionRequest request, UUID marketplaceId) {

		if (!noticeVersion.equals(request.noticeVersion())) {
			throw badRequest("UNSUPPORTED_NOTICE_VERSION");
		}

		if (request.decision() == Decision.DECLINED) {

			if (!request.scopes().isEmpty() || !request.sourceMarketplaceIds().isEmpty()
					|| !request.recipientLenderIds().isEmpty()) {
				throw badRequest("DECLINED_CONSENT_MUST_NOT_GRANT_ACCESS");
			}

			return;
		}

		if (request.scopes().isEmpty()) {
			throw badRequest("CONSENT_SCOPES_REQUIRED");
		}

		boolean dataRead = request.scopes().contains(Scope.MARKETPLACE_DATA_READ);

		if (dataRead) {
			if (!request.sourceMarketplaceIds().equals(Set.of(marketplaceId))) {
				throw badRequest("INVALID_SOURCE_MARKETPLACE");
			}
		} else if (!request.sourceMarketplaceIds().isEmpty()) {
			throw badRequest("MARKETPLACE_SCOPE_REQUIRED");
		}

		boolean lenderSharing = request.scopes().contains(Scope.LENDER_APPLICATION_SHARE);

		if (lenderSharing) {
			if (request.recipientLenderIds().isEmpty() || !validRecipientLenders(request.recipientLenderIds())) {
				throw badRequest("INVALID_RECIPIENT_LENDERS");
			}
		} else if (!request.recipientLenderIds().isEmpty()) {
			throw badRequest("LENDER_SHARING_SCOPE_REQUIRED");
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

	private String fingerprint(UUID applicationId, String sellerId, String actorSubject,
			ConsentDecisionRequest request) {

		Map<String, Object> business = new TreeMap<>();

		business.put("applicationId", applicationId.toString());
		business.put("sellerId", sellerId);
		business.put("actorSubject", actorSubject);
		business.put("decision", request.decision().name());
		business.put("expectedConsentVersion", request.expectedConsentVersion());
		business.put("noticeVersion", request.noticeVersion());

		business.put("scopes", request.scopes().stream().map(Enum::name).sorted().toList());

		business.put("sourceMarketplaceIds", sortedIds(request.sourceMarketplaceIds()));

		business.put("recipientLenderIds", sortedIds(request.recipientLenderIds()));

		String json = objectMapper.writeValueAsString(business);

		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));

			return HexFormat.of().formatHex(digest);

		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private List<String> sortedIds(Set<UUID> ids) {
		return ids.stream().map(UUID::toString).sorted().toList();
	}

	private ResponseStatusException badRequest(String code) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
	}
}