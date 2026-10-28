package com.credflow.consent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.client.FinancialApplicationClient;
import com.credflow.consent.dto.ConsentDecisionRequest;
import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.dto.FinancialWithdrawalRequest;
import com.credflow.consent.dto.FinancialWithdrawalResponse;
import com.credflow.consent.entity.ConsentStatus;

import tools.jackson.databind.ObjectMapper;

@Service
public class ConsentLifecycleService {

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final FinancialApplicationClient applications;
	private final TransactionTemplate transaction;
	private final String noticeVersion;
	private final Set<UUID> allowedLenders;
	private final FinancialConsentEvents events;

	private boolean validRecipientLenders(Set<UUID> ids) {
		for (UUID id : ids)
			if (!allowedLenders.contains(id)) {
				if (jdbc == null
						|| jdbc.queryForList("SELECT LENDER_ID FROM CF_AUTH.CF_LENDER_REGISTRY WHERE LENDER_ID=?",
								String.class, id.toString()).isEmpty())
					return false;
			}
		return true;
	}

	public ConsentLifecycleService(JdbcTemplate jdbc, ObjectMapper mapper, FinancialApplicationClient applications,
			PlatformTransactionManager transactionManager, FinancialConsentEvents events,
			@Value("${credflow.consent.notice-version}") String noticeVersion,
			@Value("${credflow.consent.allowed-lender-ids[0]}") UUID bankA,
			@Value("${credflow.consent.allowed-lender-ids[1]}") UUID nbfcB,
			@Value("${credflow.consent.allowed-lender-ids[2]:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3}") UUID bankC) {

		this.jdbc = jdbc;
		this.events = events;
		this.mapper = mapper;
		this.applications = applications;
		this.noticeVersion = noticeVersion;
		this.allowedLenders = Set.of(bankA, nbfcB, bankC);

		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	private record User(String sellerId, String subject, UUID operationId, String correlationId) {
	}

	private record Head(String consentId, int version, String sellerId, String status, int appliedVersion) {
	}

	private record Financial(int version, String sellerId, String status) {
	}

	public ConsentDecisionResponse update(UUID applicationId, ConsentDecisionRequest request, String key,
			String correlation, Jwt jwt) {

		User user = user(jwt, key, correlation);

		if (request.decision() != ConsentDecisionRequest.Decision.GRANTED || request.expectedConsentVersion() < 1) {

			throw bad("USE_GRANTED_UPDATE_WITH_CURRENT_VERSION");
		}

		var context = applications.get(applicationId);

		if (!user.sellerId().equals(context.sellerId())) {
			throw new AccessDeniedException("Seller does not own this application");
		}

		var business = new TreeMap<String, Object>();

		business.put("operation", "UPDATE_OPERATIONAL_CONSENT");
		business.put("applicationId", applicationId.toString());
		business.put("applicationRevision", context.applicationRevision());
		business.put("sellerId", user.sellerId());
		business.put("actor", user.subject());
		business.put("expectedVersion", request.expectedConsentVersion());
		business.put("noticeVersion", request.noticeVersion());

		business.put("scopes", request.scopes().stream().map(Enum::name).sorted().toList());
		business.put("marketplaces", sorted(request.sourceMarketplaceIds()));
		business.put("lenders", sorted(request.recipientLenderIds()));

		String hash = hash(business);

		return execute(() -> {
			Head head = lockHead(applicationId);

			if (!user.sellerId().equals(head.sellerId())) {
				throw new AccessDeniedException("Seller does not own this consent");
			}

			var replay = jdbc.query(
					"""
							SELECT CONSENT_VERSION, CONSENT_STATUS, REQUEST_HASH
							FROM CF_CONSENT_HISTORY
							WHERE OPERATION_ID = ?
							""", (rs,
							rowNumber) -> new String[] { rs.getString("CONSENT_VERSION"),
									rs.getString("CONSENT_STATUS"), rs.getString("REQUEST_HASH") },
					user.operationId().toString());

			if (!replay.isEmpty()) {
				String[] saved = replay.getFirst();

				if (!hash.equals(saved[2])) {
					throw conflict("IDEMPOTENCY_CONFLICT");
				}

				return response(applicationId, user.operationId(), head, Integer.parseInt(saved[0]),
						ConsentStatus.valueOf(saved[1]));
			}

			if (head.version() != request.expectedConsentVersion()) {
				throw conflict("CONSENT_VERSION_MISMATCH");
			}

			if (!java.util.Set.of("GRANTED", "DECLINED", "REVOKED").contains(head.status())) {
				throw conflict("CONSENT_NOT_GRANTED");
			}

			validate(request, context.marketplaceId());

			int nextVersion = Math.addExact(head.version(), 1);

			var payload = new TreeMap<String, Object>();

			payload.put("operationId", user.operationId().toString());
			payload.put("consentId", head.consentId());
			payload.put("consentVersion", nextVersion);
			payload.put("status", "GRANTED");
			payload.put("scopes", request.scopes().stream().map(Enum::name).sorted().toList());
			payload.put("sourceMarketplaceIds", sorted(request.sourceMarketplaceIds()));
			payload.put("recipientLenderIds", sorted(request.recipientLenderIds()));
			payload.put("purpose", "Evaluate and route this loan application.");
			payload.put("validityPolicy", "APPLICATION_OPEN");
			payload.put("noticeVersion", request.noticeVersion());
			payload.put("grantedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
			payload.put("originActor", Map.of("type", "HUMAN", "subject", user.subject()));

			String json = mapper.writeValueAsString(payload);

			int changed = jdbc.update("""
					UPDATE CF_CONSENTS
					SET CONSENT_VERSION = ?,
					    CONSENT_STATUS = 'GRANTED',
					    CONSENT_PAYLOAD = ?,
					    ROW_VERSION = ROW_VERSION + 1,
					    UPDATED_AT = SYSTIMESTAMP
					WHERE CONSENT_ID = ?
					  AND CONSENT_VERSION = ?
					  AND CONSENT_STATUS IN ('GRANTED','DECLINED','REVOKED')
					""", nextVersion, clob(json), head.consentId(), head.version());

			if (changed != 1) {
				throw conflict("CONSENT_VERSION_MISMATCH");
			}

			jdbc.update("""
					INSERT INTO CF_CONSENT_HISTORY (
					    CONSENT_ID,
					    CONSENT_VERSION,
					    OPERATION_ID,
					    CONSENT_STATUS,
					    ACTOR_SUBJECT,
					    CORRELATION_ID,
					    REQUEST_HASH,
					    CONSENT_PAYLOAD
					)
					VALUES (?, ?, ?, 'GRANTED', ?, ?, ?, ?)
					""", head.consentId(), nextVersion, user.operationId().toString(), user.subject(),
					user.correlationId(), hash, clob(json));

			jdbc.update("""
					INSERT INTO CF_CONSENT_DELIVERIES (OPERATION_ID)
					VALUES (?)
					""", user.operationId().toString());

			jdbc.update("""
					UPDATE CF_FINANCIAL_CONSENTS
					SET CONSENT_STATUS = 'REVOKED',
					    REVOKED_AT = SYSTIMESTAMP,
					    REVOCATION_REASON =
					        'OPERATIONAL_CONSENT_CHANGED'
					WHERE OPERATIONAL_CONSENT_ID = ?
					  AND CONSENT_STATUS = 'ACTIVE'
					""", head.consentId());

			jdbc.update("""
					UPDATE CF_RETRIEVAL_AUTHORIZATIONS
					SET AUTHORISATION_STATUS = 'REVOKED',
					    REVOKED_AT = SYSTIMESTAMP,
					    REVOCATION_REASON =
					        'OPERATIONAL_CONSENT_CHANGED'
					WHERE CONSENT_ID = ?
					  AND AUTHORISATION_STATUS = 'ACTIVE'
					""", head.consentId());

			return response(applicationId, user.operationId(), head, nextVersion, ConsentStatus.GRANTED);
		});
	}

	public FinancialWithdrawalResponse withdraw(UUID applicationId, FinancialWithdrawalRequest request, String key,
			String correlation, Jwt jwt) {

		User user = user(jwt, key, correlation);

		var business = new TreeMap<String, Object>();
		business.put("operation", "WITHDRAW_FINANCIAL_CONSENT");
		business.put("applicationId", applicationId.toString());
		business.put("sellerId", user.sellerId());
		business.put("actor", user.subject());
		business.put("request", request);

		String hash = hash(business);

		return execute(() -> {
			// Keep lock ordering consistent with grant and finalisation.
			lockHead(applicationId);

			var financialRows = jdbc.query("""
					SELECT
					    CONSENT_VERSION,
					    SELLER_ID,
					    CONSENT_STATUS
					FROM CF_FINANCIAL_CONSENTS
					WHERE CONSENT_ID = ?
					  AND APPLICATION_ID = ?
					FOR UPDATE
					""",
					(rs, rowNumber) -> new Financial(rs.getInt("CONSENT_VERSION"), rs.getString("SELLER_ID"),
							rs.getString("CONSENT_STATUS")),
					request.financialConsentId().toString(), applicationId.toString());

			if (financialRows.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FINANCIAL_CONSENT_NOT_FOUND");
			}

			Financial financial = financialRows.getFirst();

			if (!user.sellerId().equals(financial.sellerId())) {
				throw new AccessDeniedException("Seller does not own this financial consent");
			}

			var replay = jdbc.query("""
					SELECT REQUEST_HASH, RESPONSE_JSON
					FROM CF_FINANCIAL_CONSENT_ACTIONS
					WHERE OPERATION_ID = ?
					""",
					(rs, rowNumber) -> new String[] { rs.getString("REQUEST_HASH"), rs.getString("RESPONSE_JSON") },
					user.operationId().toString());

			if (!replay.isEmpty()) {
				if (!hash.equals(replay.getFirst()[0])) {
					throw conflict("IDEMPOTENCY_CONFLICT");
				}

				return mapper.readValue(replay.getFirst()[1], FinancialWithdrawalResponse.class);
			}

			if (financial.version() != request.expectedFinancialConsentVersion()) {

				throw conflict("FINANCIAL_CONSENT_VERSION_MISMATCH");
			}

			if ("REVOKED".equals(financial.status())) {
				throw conflict("FINANCIAL_CONSENT_ALREADY_REVOKED");
			}

			OffsetDateTime revokedAt = OffsetDateTime.now(ZoneOffset.UTC);

			jdbc.update("""
					UPDATE CF_FINANCIAL_CONSENTS
					SET CONSENT_STATUS = 'REVOKED',
					    REVOKED_AT = ?,
					    REVOCATION_REASON = ?
					WHERE CONSENT_ID = ?
					""", revokedAt, request.reasonCode(), request.financialConsentId().toString());

			jdbc.update("""
					UPDATE CF_RETRIEVAL_AUTHORIZATIONS
					SET AUTHORISATION_STATUS = 'REVOKED',
					    REVOKED_AT = ?,
					    REVOCATION_REASON = ?
					WHERE FINANCIAL_CONSENT_ID = ?
					  AND AUTHORISATION_STATUS = 'ACTIVE'
					""", revokedAt, request.reasonCode(), request.financialConsentId().toString());

			var response = new FinancialWithdrawalResponse(user.operationId(), request.financialConsentId(),
					financial.version(), applicationId, "REVOKED", revokedAt);

			jdbc.update("""
					INSERT INTO CF_FINANCIAL_CONSENT_ACTIONS (
					    OPERATION_ID,
					    APPLICATION_ID,
					    FINANCIAL_CONSENT_ID,
					    REQUEST_HASH,
					    ACTOR_SUBJECT,
					    CORRELATION_ID,
					    RESPONSE_JSON
					)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""", user.operationId().toString(), applicationId.toString(),
					request.financialConsentId().toString(), hash, user.subject(), user.correlationId(),
					clob(mapper.writeValueAsString(response)));

			events.append(request.financialConsentId(), "withdrawn", revokedAt, user.subject(), user.correlationId());
			return response;
		});
	}

	private Head lockHead(UUID applicationId) {

		var heads = jdbc.query("""
				SELECT
				    CONSENT_ID,
				    CONSENT_VERSION,
				    SELLER_ID,
				    CONSENT_STATUS,
				    ORCHESTRATION_APPLIED_VERSION
				FROM CF_CONSENTS
				WHERE APPLICATION_ID = ?
				FOR UPDATE
				""", (rs, rowNumber) -> new Head(rs.getString("CONSENT_ID"), rs.getInt("CONSENT_VERSION"),
				rs.getString("SELLER_ID"), rs.getString("CONSENT_STATUS"), rs.getInt("ORCHESTRATION_APPLIED_VERSION")),
				applicationId.toString());

		if (heads.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONSENT_NOT_FOUND");
		}

		return heads.getFirst();
	}

	private void validate(ConsentDecisionRequest request, UUID marketplaceId) {

		if (!noticeVersion.equals(request.noticeVersion())) {
			throw bad("UNSUPPORTED_NOTICE_VERSION");
		}

		if (request.scopes().isEmpty()) {
			throw bad("CONSENT_SCOPES_REQUIRED");
		}

		boolean read = request.scopes().contains(ConsentDecisionRequest.Scope.MARKETPLACE_DATA_READ);

		if (read ? !request.sourceMarketplaceIds().equals(Set.of(marketplaceId))
				: !request.sourceMarketplaceIds().isEmpty()) {

			throw bad("INVALID_SOURCE_MARKETPLACE");
		}

		boolean share = request.scopes().contains(ConsentDecisionRequest.Scope.LENDER_APPLICATION_SHARE);

		if (share) {
			if (request.recipientLenderIds().isEmpty() || !validRecipientLenders(request.recipientLenderIds())) {

				throw bad("INVALID_RECIPIENT_LENDERS");
			}

		} else if (!request.recipientLenderIds().isEmpty()) {
			throw bad("LENDER_SHARING_SCOPE_REQUIRED");
		}
	}

	private User user(Jwt jwt, String key, String correlation) {

		if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw new AccessDeniedException("Seller is required");
		}

		String sellerId = jwt.getClaimAsString("sellerId");

		if (sellerId == null || sellerId.isBlank()) {
			throw new AccessDeniedException("Seller ID is required");
		}

		UUID operation;

		try {
			operation = UUID.fromString(key);
		} catch (RuntimeException exception) {
			throw bad("IDEMPOTENCY_KEY_MUST_BE_UUID");
		}

		String trace = correlation == null ? UUID.randomUUID().toString() : correlation;

		if (!trace.matches("[A-Za-z0-9._:-]{1,100}")) {
			throw bad("INVALID_CORRELATION_ID");
		}

		return new User(sellerId, jwt.getSubject(), operation, trace);
	}

	private ConsentDecisionResponse response(UUID applicationId, UUID operationId, Head head, int version,
			ConsentStatus status) {

		return new ConsentDecisionResponse(operationId, UUID.fromString(head.consentId()), applicationId, version,
				status, head.appliedVersion() >= version ? "APPLIED" : "PENDING");
	}

	private <T> T execute(Supplier<T> action) {

		try {
			return transaction.execute(status -> action.get());

		} catch (DuplicateKeyException exception) {
			// First transaction has rolled back before replay lookup.
			return transaction.execute(status -> action.get());
		}
	}

	private List<String> sorted(Set<UUID> ids) {
		return ids.stream().map(UUID::toString).sorted().toList();
	}

	private SqlParameterValue clob(String value) {
		return new SqlParameterValue(Types.CLOB, value);
	}

	private String hash(Object value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private ResponseStatusException bad(String reason) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
	}

	private ResponseStatusException conflict(String reason) {
		return new ResponseStatusException(HttpStatus.CONFLICT, reason);
	}
}