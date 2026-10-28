package com.credflow.consent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.client.ApplicationOwnershipClient;
import com.credflow.consent.client.ApplicationOwnershipClient.ApplicationContext;
import com.credflow.consent.dto.FinancialConsentRequest;
import com.credflow.consent.dto.FinancialConsentResponse;
import com.credflow.consent.service.VerificationPrecheckService.StoredConsent;

import tools.jackson.databind.ObjectMapper;

@Service
public class FinancialConsentService {

	private static final String SCOPE = "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY";

	private final ApplicationOwnershipClient applications;
	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transaction;
	private final String noticeVersion;
	private final long validitySeconds;
	private final FinancialConsentEvents events;

	public FinancialConsentService(ApplicationOwnershipClient applications, JdbcTemplate jdbc,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager, FinancialConsentEvents events,
			@Value("${credflow.financial-consent.notice-version:v1}") String noticeVersion,
			@Value("${credflow.financial-consent.validity-seconds:86400}") long validitySeconds) {

		if (noticeVersion.isBlank() || noticeVersion.length() > 100 || validitySeconds < 60
				|| validitySeconds > 2592000) {

			throw new IllegalArgumentException("INVALID_FINANCIAL_CONSENT_CONFIGURATION");
		}

		this.applications = applications;
		this.events = events;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.noticeVersion = noticeVersion;
		this.validitySeconds = validitySeconds;

		this.transaction = new TransactionTemplate(transactionManager);

		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	private record OperationalConsent(String consentId, int version, String sellerId, String status, String payload) {
	}

	private record SavedGrant(String requestHash, FinancialConsentResponse response) {
	}

	public FinancialConsentResponse grant(UUID applicationId, FinancialConsentRequest request, String idempotencyKey,
			String correlationHeader, Jwt jwt) {

		if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {

			throw new AccessDeniedException("Authenticated seller is required");
		}

		String sellerId = jwt.getClaimAsString("sellerId");

		if (sellerId == null || sellerId.isBlank()) {
			throw new AccessDeniedException("Authenticated seller identity is required");
		}

		if (!Boolean.TRUE.equals(request.accepted())) {
			throw badRequest("EXPLICIT_FINANCIAL_CONSENT_REQUIRED");
		}

		UUID operationId = parseOperationId(idempotencyKey);
		String correlationId = resolveCorrelation(correlationHeader);

		ApplicationContext context = applications.getContext(applicationId);

		if (!sellerId.equals(context.sellerId())) {
			throw new AccessDeniedException("Seller does not own this application");
		}

		if (context.applicationRevision() == null || context.applicationRevision() < 1) {

			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_APPLICATION_CONTEXT");
		}

		Map<String, Object> business = new TreeMap<>();

		business.put("operation", "GRANT_FINANCIAL_CONSENT");
		business.put("applicationId", applicationId.toString());
		business.put("sellerId", sellerId);
		business.put("actorSubject", jwt.getSubject());
		business.put("applicationRevision", request.applicationRevision());
		business.put("expectedOperationalConsentVersion", request.expectedOperationalConsentVersion());
		business.put("expectedFinancialConsentVersion", request.expectedFinancialConsentVersion());
		business.put("noticeVersion", request.noticeVersion());
		business.put("accepted", request.accepted());

		String requestHash = fingerprint(objectMapper.writeValueAsString(business));

		FinancialConsentResponse response;

		try {
			response = transaction.execute(status -> recordGrant(applicationId, request, context, sellerId,
					jwt.getSubject(), operationId, correlationId, requestHash));

		} catch (DuplicateKeyException exception) {
			// The failed transaction has rolled back before this lookup.
			response = transaction.execute(status -> findReplay(operationId, requestHash));

			if (response == null) {
				throw exception;
			}
		}

		if (response == null) {
			throw new IllegalStateException("FINANCIAL_CONSENT_RETURNED_NO_RESPONSE");
		}

		return response;
	}

	private FinancialConsentResponse recordGrant(UUID applicationId, FinancialConsentRequest request,
			ApplicationContext context, String sellerId, String actorSubject, UUID operationId, String correlationId,
			String requestHash) {

		// Shared lock with operational revocation and finalisation.
		var operationalRows = jdbc.query("""
				SELECT
				    CONSENT_ID,
				    CONSENT_VERSION,
				    SELLER_ID,
				    CONSENT_STATUS,
				    CONSENT_PAYLOAD
				FROM CF_CONSENTS
				WHERE APPLICATION_ID = ?
				FOR UPDATE
				""",
				(rs, rowNumber) -> new OperationalConsent(rs.getString("CONSENT_ID"), rs.getInt("CONSENT_VERSION"),
						rs.getString("SELLER_ID"), rs.getString("CONSENT_STATUS"), rs.getString("CONSENT_PAYLOAD")),
				applicationId.toString());

		if (operationalRows.isEmpty()) {
			throw conflict("OPERATIONAL_CONSENT_REQUIRED");
		}

		OperationalConsent operational = operationalRows.getFirst();

		if (!sellerId.equals(operational.sellerId())) {
			throw new AccessDeniedException("Seller does not own this consent");
		}

		var replay = findReplay(operationId, requestHash);

		if (replay != null) {
			return replay;
		}

		if (!request.applicationRevision().equals(context.applicationRevision())) {

			throw conflict("APPLICATION_REVISION_MISMATCH");
		}

		if (request.expectedOperationalConsentVersion() != operational.version()) {

			throw conflict("OPERATIONAL_CONSENT_VERSION_MISMATCH");
		}

		if (!"GRANTED".equals(operational.status())) {
			throw conflict("OPERATIONAL_CONSENT_NOT_GRANTED");
		}

		if (!noticeVersion.equals(request.noticeVersion())) {
			throw badRequest("UNSUPPORTED_FINANCIAL_NOTICE_VERSION");
		}

		StoredConsent payload = objectMapper.readValue(operational.payload(), StoredConsent.class);

		if (payload == null || payload.scopes() == null || !payload.scopes().contains("MARKETPLACE_DATA_READ")
				|| payload.sourceMarketplaceIds() == null
				|| !payload.sourceMarketplaceIds().contains(context.marketplaceId())) {

			throw conflict("MARKETPLACE_DATA_PERMISSION_REQUIRED");
		}

		Integer latestVersion = jdbc.queryForObject("""
				SELECT NVL(MAX(CONSENT_VERSION), 0)
				FROM CF_FINANCIAL_CONSENTS
				WHERE APPLICATION_ID = ?
				  AND APPLICATION_REVISION = ?
				""", Integer.class, applicationId.toString(), request.applicationRevision());

		int currentVersion = latestVersion == null ? 0 : latestVersion;

		if (request.expectedFinancialConsentVersion() != currentVersion) {

			throw conflict("FINANCIAL_CONSENT_VERSION_MISMATCH");
		}

		int nextVersion = Math.addExact(currentVersion, 1);

		UUID financialConsentId = UUID.randomUUID();

		OffsetDateTime grantedAt = OffsetDateTime.now(ZoneOffset.UTC);

		OffsetDateTime expiresAt = grantedAt.plusSeconds(validitySeconds);

		// A new explicit grant supersedes earlier grants for this revision.
		jdbc.update("""
				UPDATE CF_FINANCIAL_CONSENTS
				SET CONSENT_STATUS = 'REVOKED',
				    REVOKED_AT = ?,
				    REVOCATION_REASON = 'SUPERSEDED'
				WHERE APPLICATION_ID = ?
				  AND APPLICATION_REVISION = ?
				  AND CONSENT_STATUS = 'ACTIVE'
				""", grantedAt, applicationId.toString(), request.applicationRevision());

		jdbc.update("""
				UPDATE CF_RETRIEVAL_AUTHORIZATIONS
				SET AUTHORISATION_STATUS = 'REVOKED',
				    REVOKED_AT = ?,
				    REVOCATION_REASON = 'FINANCIAL_CONSENT_SUPERSEDED'
				WHERE APPLICATION_ID = ?
				  AND APPLICATION_REVISION = ?
				  AND AUTHORISATION_STATUS = 'ACTIVE'
				""", grantedAt, applicationId.toString(), request.applicationRevision());

		jdbc.update("""
				INSERT INTO CF_FINANCIAL_CONSENTS (
				    CONSENT_ID,
				    CONSENT_VERSION,
				    APPLICATION_ID,
				    APPLICATION_REVISION,
				    OPERATIONAL_CONSENT_ID,
				    OPERATIONAL_CONSENT_VERSION,
				    SELLER_ID,
				    MARKETPLACE_ID,
				    CONSENT_SCOPE,
				    CONSENT_STATUS,
				    NOTICE_VERSION,
				    ACTOR_SUBJECT,
				    OPERATION_ID,
				    REQUEST_HASH,
				    GRANTED_AT,
				    EXPIRES_AT,
				    CORRELATION_ID
				)
				VALUES (
				    ?, ?, ?, ?, ?, ?, ?, ?, ?,
				    'ACTIVE', ?, ?, ?, ?, ?, ?, ?
				)
				""", financialConsentId.toString(), nextVersion, applicationId.toString(),
				request.applicationRevision(), operational.consentId(), operational.version(), sellerId,
				context.marketplaceId().toString(), SCOPE, request.noticeVersion(), actorSubject,
				operationId.toString(), requestHash, grantedAt, expiresAt, correlationId);

		events.append(financialConsentId, "granted", grantedAt, actorSubject, correlationId);

		return new FinancialConsentResponse(operationId, financialConsentId, nextVersion, applicationId,
				request.applicationRevision(), UUID.fromString(operational.consentId()), operational.version(), SCOPE,
				"ACTIVE", grantedAt, expiresAt);
	}

	private FinancialConsentResponse findReplay(UUID operationId, String requestHash) {

		var rows = jdbc.query("""
				SELECT *
				FROM CF_FINANCIAL_CONSENTS
				WHERE OPERATION_ID = ?
				""", (rs, rowNumber) -> {
			OffsetDateTime expiresAt = rs.getObject("EXPIRES_AT", OffsetDateTime.class);

			String status = rs.getString("CONSENT_STATUS");

			if ("ACTIVE".equals(status) && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
				status = "EXPIRED";
			}

			return new SavedGrant(rs.getString("REQUEST_HASH"),
					new FinancialConsentResponse(UUID.fromString(rs.getString("OPERATION_ID")),
							UUID.fromString(rs.getString("CONSENT_ID")), rs.getInt("CONSENT_VERSION"),
							UUID.fromString(rs.getString("APPLICATION_ID")), rs.getInt("APPLICATION_REVISION"),
							UUID.fromString(rs.getString("OPERATIONAL_CONSENT_ID")),
							rs.getInt("OPERATIONAL_CONSENT_VERSION"), rs.getString("CONSENT_SCOPE"), status,
							rs.getObject("GRANTED_AT", OffsetDateTime.class), expiresAt));
		}, operationId.toString());

		if (rows.isEmpty()) {
			return null;
		}

		SavedGrant saved = rows.getFirst();

		if (!saved.requestHash().equals(requestHash)) {
			throw conflict("IDEMPOTENCY_CONFLICT");
		}

		return saved.response();
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
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));

		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ResponseStatusException conflict(String code) {
		return new ResponseStatusException(HttpStatus.CONFLICT, code);
	}

	private ResponseStatusException badRequest(String code) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
	}
}