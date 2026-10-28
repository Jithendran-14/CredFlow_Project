package com.credflow.consent.service;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.client.FinancialApplicationClient.Context;
import com.credflow.consent.dto.FinancialDataModels.Dataset;
import com.credflow.consent.dto.FinancialDataModels.Request;

import tools.jackson.databind.ObjectMapper;

@Service
public class FinancialRetrievalStore {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final String sourceProvider;

	public FinancialRetrievalStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
			@Value("${credflow.clients.financial.source-provider:" + "member2-financial-mock}") String sourceProvider) {

		if (sourceProvider.isBlank() || sourceProvider.length() > 100) {
			throw new IllegalArgumentException("INVALID_FINANCIAL_SOURCE_PROVIDER");
		}

		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.sourceProvider = sourceProvider;
	}

	public record Grant(String sellerId, UUID marketplaceId, UUID financialConsentId, int financialConsentVersion,
			OffsetDateTime checkedAt, OffsetDateTime issuedAt) {
	}

	public record Job(UUID providerRequestId, LocalDate asOfDate, String sourceProvider, String sellerFixtureId,
			String correlationId, Dataset cachedDataset) {
	}

	public record Prepared(Grant grant, Job job) {
	}

	private record Seed(String operationalConsentId, String financialConsentId) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Prepared prepare(UUID applicationId, Request request, String clientId, UUID operationId, String requestHash,
			String correlationId, Context context) {

		Grant grant = requireAccess(applicationId, request, clientId, context);

		var keys = jdbc.query("""
				SELECT REQUEST_HASH, CLIENT_ID
				FROM CF_FINANCIAL_RETRIEVAL_KEYS
				WHERE OPERATION_ID = ?
				""", (rs, rowNumber) -> new String[] { rs.getString("REQUEST_HASH"), rs.getString("CLIENT_ID") },
				operationId.toString());

		if (!keys.isEmpty() && (!requestHash.equals(keys.getFirst()[0]) || !clientId.equals(keys.getFirst()[1]))) {

			throw conflict("IDEMPOTENCY_CONFLICT");
		}

		Job job = findJob(request.verificationId());

		if (job == null) {
			var mappings = jdbc.queryForList("""
					SELECT SELLER_FIXTURE_ID
					FROM CF_FINANCIAL_PROVIDER_MAP
					WHERE SELLER_ID = ?
					  AND MARKETPLACE_ID = ?
					  AND SOURCE_PROVIDER = ?
					  AND MAPPING_STATUS = 'ACTIVE'
					""", String.class, grant.sellerId(), grant.marketplaceId().toString(), sourceProvider);

			if (mappings.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"FINANCIAL_PROVIDER_MAPPING_REQUIRED");
			}

			UUID providerRequestId = UUID.randomUUID();

			jdbc.update("""
					INSERT INTO CF_FINANCIAL_RETRIEVALS (
					    VERIFICATION_ID,
					    AS_OF_DATE,
					    SOURCE_PROVIDER,
					    SELLER_FIXTURE_ID,
					    PROVIDER_REQUEST_ID,
					    CORRELATION_ID
					)
					VALUES (?, ?, ?, ?, ?, ?)
					""", request.verificationId().toString(), Date.valueOf(request.asOfDate()), sourceProvider,
					mappings.getFirst(), providerRequestId.toString(), correlationId);

			job = findJob(request.verificationId());
		}

		requireJobBinding(job, request, grant);

		if (keys.isEmpty()) {
			jdbc.update("""
					INSERT INTO CF_FINANCIAL_RETRIEVAL_KEYS (
					    OPERATION_ID,
					    VERIFICATION_ID,
					    CLIENT_ID,
					    REQUEST_HASH,
					    CORRELATION_ID
					)
					VALUES (?, ?, ?, ?, ?)
					""", operationId.toString(), request.verificationId().toString(), clientId, requestHash,
					correlationId);
		}

		return new Prepared(grant, job);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Prepared complete(UUID applicationId, Request request, String clientId, Context context, Dataset dataset,
			String contentHash) {

		Grant grant = requireAccess(applicationId, request, clientId, context);

		Job job = findJob(request.verificationId());

		requireJobBinding(job, request, grant);

		if (job.cachedDataset() != null) {
			String existingHash = FinancialRetrievalService
					.sha256(objectMapper.writeValueAsString(job.cachedDataset()));

			if (!existingHash.equals(contentHash)) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "FINANCIAL_PROVIDER_CHANGED_PINNED_DATA");
			}

			return new Prepared(grant, job);
		}

		var existingHashes = jdbc.queryForList("""
				SELECT CONTENT_HASH
				FROM CF_FINANCIAL_DATASETS
				WHERE SOURCE_PROVIDER = ?
				  AND DATA_REFERENCE = ?
				  AND DATA_VERSION = ?
				""", String.class, dataset.sourceProvider(), dataset.dataReference(), dataset.dataVersion());

		if (!existingHashes.isEmpty() && !contentHash.equals(existingHashes.getFirst())) {

			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "FINANCIAL_DATASET_VERSION_CONFLICT");
		}

		if (existingHashes.isEmpty()) {
			jdbc.update("""
					INSERT INTO CF_FINANCIAL_DATASETS (
					    SOURCE_PROVIDER,
					    DATA_REFERENCE,
					    DATA_VERSION,
					    CONTENT_HASH,
					    DATASET_JSON
					)
					VALUES (?, ?, ?, ?, ?)
					""", dataset.sourceProvider(), dataset.dataReference(), dataset.dataVersion(), contentHash,
					new SqlParameterValue(Types.CLOB, objectMapper.writeValueAsString(dataset)));
		}

		jdbc.update("""
				UPDATE CF_FINANCIAL_RETRIEVALS
				SET DATA_REFERENCE = ?,
				    DATA_VERSION = ?
				WHERE VERIFICATION_ID = ?
				""", dataset.dataReference(), dataset.dataVersion(), request.verificationId().toString());

		return new Prepared(grant, findJob(request.verificationId()));
	}

	private Grant requireAccess(UUID applicationId, Request request, String clientId, Context context) {

		var seeds = jdbc.query("""
				SELECT CONSENT_ID, FINANCIAL_CONSENT_ID
				FROM CF_RETRIEVAL_AUTHORIZATIONS
				WHERE VERIFICATION_ID = ?
				  AND AUTHORISED_RETRIEVAL_REFERENCE = ?
				  AND APPLICATION_ID = ?
				""", (rs, rowNumber) -> new Seed(rs.getString("CONSENT_ID"), rs.getString("FINANCIAL_CONSENT_ID")),
				request.verificationId().toString(), request.authorisedRetrievalReference(), applicationId.toString());

		if (seeds.isEmpty() || seeds.getFirst().financialConsentId() == null) {
			throw denied();
		}

		Seed seed = seeds.getFirst();

		// Same order as grant/revocation: operational, financial, auth.
		jdbc.query("""
				SELECT CONSENT_ID
				FROM CF_CONSENTS
				WHERE CONSENT_ID = ?
				FOR UPDATE
				""", (rs, rowNumber) -> rs.getString(1), seed.operationalConsentId());

		jdbc.query("""
				SELECT CONSENT_ID
				FROM CF_FINANCIAL_CONSENTS
				WHERE CONSENT_ID = ?
				FOR UPDATE
				""", (rs, rowNumber) -> rs.getString(1), seed.financialConsentId());

		jdbc.query("""
				SELECT VERIFICATION_ID
				FROM CF_RETRIEVAL_AUTHORIZATIONS
				WHERE VERIFICATION_ID = ?
				FOR UPDATE
				""", (rs, rowNumber) -> rs.getString(1), request.verificationId().toString());

		var grants = jdbc.query("""
				SELECT
				    a.SELLER_ID,
				    a.MARKETPLACE_ID,
				    a.FINANCIAL_CONSENT_ID,
				    a.FINANCIAL_CONSENT_VERSION,
				    a.ISSUED_AT,
				    i.PROVIDER_CHECKED_AT
				FROM CF_RETRIEVAL_AUTHORIZATIONS a
				JOIN CF_CONSENTS c
				  ON c.CONSENT_ID = a.CONSENT_ID
				 AND c.CONSENT_VERSION = a.CONSENT_VERSION
				 AND c.APPLICATION_ID = a.APPLICATION_ID
				 AND c.SELLER_ID = a.SELLER_ID
				 AND c.CONSENT_STATUS = 'GRANTED'
				JOIN CF_FINANCIAL_CONSENTS f
				  ON f.CONSENT_ID = a.FINANCIAL_CONSENT_ID
				 AND f.CONSENT_VERSION = a.FINANCIAL_CONSENT_VERSION
				 AND f.APPLICATION_ID = a.APPLICATION_ID
				 AND f.APPLICATION_REVISION = a.APPLICATION_REVISION
				 AND f.OPERATIONAL_CONSENT_ID = a.CONSENT_ID
				 AND f.OPERATIONAL_CONSENT_VERSION = a.CONSENT_VERSION
				 AND f.SELLER_ID = a.SELLER_ID
				 AND f.MARKETPLACE_ID = a.MARKETPLACE_ID
				 AND f.CONSENT_STATUS = 'ACTIVE'
				 AND f.CONSENT_SCOPE =
				     'FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY'
				 AND f.GRANTED_AT <= SYSTIMESTAMP
				 AND f.EXPIRES_AT > SYSTIMESTAMP
				JOIN CF_VERIFICATION_RESULTS v
				  ON v.VERIFICATION_ID = a.VERIFICATION_ID
				 AND v.APPLICATION_ID = a.APPLICATION_ID
				 AND v.APPLICATION_REVISION = a.APPLICATION_REVISION
				 AND v.CONSENT_ID = a.CONSENT_ID
				 AND v.CONSENT_VERSION = a.CONSENT_VERSION
				 AND v.RESULT_SOURCE = 'PROVIDER'
				 AND v.RESULT_STATUS = 'VERIFIED'
				JOIN CF_VERIFICATION_REQUESTS q
				  ON q.VERIFICATION_ID = a.VERIFICATION_ID
				 AND q.PROCESSING_STATUS = 'COMPLETED'
				JOIN CF_IDENTITY_CHECKS i
				  ON i.VERIFICATION_ID = a.VERIFICATION_ID
				 AND i.APPLICATION_ID = a.APPLICATION_ID
				 AND i.APPLICATION_REVISION = a.APPLICATION_REVISION
				 AND i.FIXTURE_IDENTITY_ID = v.FIXTURE_IDENTITY_ID
				 AND i.CHECK_STATUS = 'RECEIVED'
				 AND i.PROVIDER_STATUS = 'VERIFIED'
				JOIN CF_SELLER_IDENTITY_MAP m
				  ON m.SELLER_ID = a.SELLER_ID
				 AND m.MARKETPLACE_ID = a.MARKETPLACE_ID
				 AND m.FIXTURE_IDENTITY_ID = i.FIXTURE_IDENTITY_ID
				 AND m.MAPPING_STATUS = 'ACTIVE'
				WHERE a.VERIFICATION_ID = ?
				  AND a.AUTHORISED_RETRIEVAL_REFERENCE = ?
				  AND a.APPLICATION_ID = ?
				  AND a.APPLICATION_REVISION = ?
				  AND a.AUTHORISED_CLIENT_ID = ?
				  AND a.AUTHORISATION_STATUS = 'ACTIVE'
				  AND a.VALIDITY_POLICY = 'APPLICATION_OPEN'
				  AND a.PURPOSE = 'ELIGIBILITY_EVALUATION'
				  AND a.ISSUED_AT <= SYSTIMESTAMP
				  AND a.EXPIRES_AT > SYSTIMESTAMP
				  AND f.CONSENT_VERSION = (
				      SELECT MAX(latest.CONSENT_VERSION)
				      FROM CF_FINANCIAL_CONSENTS latest
				      WHERE latest.APPLICATION_ID = f.APPLICATION_ID
				        AND latest.APPLICATION_REVISION =
				            f.APPLICATION_REVISION
				  )
				""",
				(rs, rowNumber) -> new Grant(rs.getString("SELLER_ID"), UUID.fromString(rs.getString("MARKETPLACE_ID")),
						UUID.fromString(rs.getString("FINANCIAL_CONSENT_ID")), rs.getInt("FINANCIAL_CONSENT_VERSION"),
						rs.getObject("PROVIDER_CHECKED_AT", OffsetDateTime.class),
						rs.getObject("ISSUED_AT", OffsetDateTime.class)),
				request.verificationId().toString(), request.authorisedRetrievalReference(), applicationId.toString(),
				request.applicationRevision(), clientId);

		if (grants.isEmpty()) {
			throw denied();
		}

		Grant grant = grants.getFirst();

		if (!applicationId.equals(context.applicationId())
				|| !request.applicationRevision().equals(context.applicationRevision())
				|| !grant.sellerId().equals(context.sellerId())
				|| !grant.marketplaceId().equals(context.marketplaceId())) {

			throw denied();
		}

		LocalDate issuedDate = grant.issuedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();

		LocalDate today = LocalDate.now(ZoneOffset.UTC);

		if (request.asOfDate().isBefore(issuedDate) || request.asOfDate().isAfter(today)) {
			throw denied();
		}

		return grant;
	}

	private Job findJob(UUID verificationId) {

		var rows = jdbc.query("""
				SELECT
				    r.PROVIDER_REQUEST_ID,
				    r.AS_OF_DATE,
				    r.SOURCE_PROVIDER,
				    r.SELLER_FIXTURE_ID,
				    r.CORRELATION_ID,
				    d.DATASET_JSON
				FROM CF_FINANCIAL_RETRIEVALS r
				LEFT JOIN CF_FINANCIAL_DATASETS d
				  ON d.SOURCE_PROVIDER = r.SOURCE_PROVIDER
				 AND d.DATA_REFERENCE = r.DATA_REFERENCE
				 AND d.DATA_VERSION = r.DATA_VERSION
				WHERE r.VERIFICATION_ID = ?
				""", (rs, rowNumber) -> {
			String json = rs.getString("DATASET_JSON");

			return new Job(UUID.fromString(rs.getString("PROVIDER_REQUEST_ID")), rs.getDate("AS_OF_DATE").toLocalDate(),
					rs.getString("SOURCE_PROVIDER"), rs.getString("SELLER_FIXTURE_ID"), rs.getString("CORRELATION_ID"),
					json == null ? null : objectMapper.readValue(json, Dataset.class));
		}, verificationId.toString());

		return rows.isEmpty() ? null : rows.getFirst();
	}

	private void requireJobBinding(Job job, Request request, Grant grant) {

		if (job == null) {
			throw new IllegalStateException("FINANCIAL_RETRIEVAL_JOB_MISSING");
		}

		if (!job.asOfDate().equals(request.asOfDate())) {
			throw conflict("FINANCIAL_WINDOW_ALREADY_BOUND");
		}

		Integer activeMapping = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM CF_FINANCIAL_PROVIDER_MAP
				WHERE SELLER_ID = ?
				  AND MARKETPLACE_ID = ?
				  AND SOURCE_PROVIDER = ?
				  AND SELLER_FIXTURE_ID = ?
				  AND MAPPING_STATUS = 'ACTIVE'
				""", Integer.class, grant.sellerId(), grant.marketplaceId().toString(), job.sourceProvider(),
				job.sellerFixtureId());

		if (activeMapping == null || activeMapping != 1) {
			throw denied();
		}
	}

	private ResponseStatusException denied() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "FINANCIAL_ACCESS_DENIED");
	}

	private ResponseStatusException conflict(String reason) {
		return new ResponseStatusException(HttpStatus.CONFLICT, reason);
	}
}