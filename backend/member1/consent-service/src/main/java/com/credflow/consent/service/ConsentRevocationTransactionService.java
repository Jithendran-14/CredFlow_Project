package com.credflow.consent.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.dto.ConsentRevocationRequest;
import com.credflow.consent.entity.ConsentDeliveryEntity;
import com.credflow.consent.entity.ConsentEntity;
import com.credflow.consent.entity.ConsentHistoryEntity;
import com.credflow.consent.entity.ConsentStatus;
import com.credflow.consent.repository.ConsentDeliveryRepository;
import com.credflow.consent.repository.ConsentHistoryRepository;
import com.credflow.consent.repository.ConsentRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ConsentRevocationTransactionService {

    private final ConsentRepository consents;
    private final ConsentHistoryRepository history;
    private final ConsentDeliveryRepository deliveries;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConsentRevocationTransactionService(
            ConsentRepository consents,
            ConsentHistoryRepository history,
            ConsentDeliveryRepository deliveries,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {

        this.consents = consents;
        this.history = history;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConsentDecisionResponse revoke(
            UUID applicationId,
            String sellerId,
            String actorSubject,
            UUID operationId,
            String correlationId,
            String requestHash,
            ConsentRevocationRequest request) {

        ConsentEntity consent =
                consents.findForUpdateByApplicationId(
                        applicationId.toString()
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "CONSENT_NOT_FOUND"
                ));

        if (!sellerId.equals(consent.getSellerId())) {
            throw new AccessDeniedException(
                    "Seller does not own this consent"
            );
        }

        // Replays must work after the original request changed the version.
        var existingOperation =
                history.findByOperationId(operationId.toString());

        if (existingOperation.isPresent()) {
            return replay(existingOperation.get(), requestHash);
        }

        if (!request.expectedConsentVersion().equals(
                consent.getConsentVersion())) {

            throw conflict("CONSENT_VERSION_MISMATCH");
        }

        if (consent.getConsentStatus() == ConsentStatus.REVOKED) {
            throw conflict("CONSENT_ALREADY_REVOKED");
        }

        if (consent.getConsentStatus() != ConsentStatus.GRANTED) {
            throw conflict("CONSENT_NOT_GRANTED");
        }

        int nextVersion = Math.addExact(
                consent.getConsentVersion(),
                1
        );

        var existingPayload = objectMapper.readTree(
                consent.getConsentPayload()
        );

        if (!(existingPayload instanceof ObjectNode payload)) {
            throw new IllegalStateException(
                    "INVALID_STORED_CONSENT_PAYLOAD"
            );
        }

        payload.put("operationId", operationId.toString());
        payload.put("consentId", consent.getConsentId());
        payload.put("consentVersion", nextVersion);
        payload.put("status", "REVOKED");

        payload.put(
                "revokedAt",
                OffsetDateTime.now(ZoneOffset.UTC).toString()
        );

        payload.put("revocationReason", request.reasonCode());

        // The revoked version grants no access.
        payload.putArray("scopes");
        payload.putArray("sourceMarketplaceIds");
        payload.putArray("recipientLenderIds");

        payload.remove("grantedAt");

        var originActor = payload.putObject("originActor");
        originActor.put("type", "HUMAN");
        originActor.put("subject", actorSubject);

        String payloadJson = objectMapper.writeValueAsString(
                payload
        );

        consent.revoke(payloadJson);
        consents.saveAndFlush(consent);

        ConsentHistoryEntity entry = new ConsentHistoryEntity(
                consent.getConsentId(),
                nextVersion,
                operationId,
                ConsentStatus.REVOKED,
                actorSubject,
                correlationId,
                requestHash,
                payloadJson
        );

        history.saveAndFlush(entry);

        deliveries.saveAndFlush(
                ConsentDeliveryEntity.pending(operationId)
        );

        // Revoke financial permissions bound to this operational consent.
        jdbc.update("""
                UPDATE CF_FINANCIAL_CONSENTS
                SET CONSENT_STATUS = 'REVOKED',
                    REVOKED_AT = SYSTIMESTAMP,
                    REVOCATION_REASON = ?
                WHERE OPERATIONAL_CONSENT_ID = ?
                  AND CONSENT_STATUS = 'ACTIVE'
                """,
                request.reasonCode(),
                consent.getConsentId()
        );

        // Revoke retrieval authorisations for every consent version.
        jdbc.update("""
                UPDATE CF_RETRIEVAL_AUTHORIZATIONS
                SET AUTHORISATION_STATUS = 'REVOKED',
                    REVOKED_AT = SYSTIMESTAMP,
                    REVOCATION_REASON = ?
                WHERE CONSENT_ID = ?
                  AND AUTHORISATION_STATUS = 'ACTIVE'
                """,
                request.reasonCode(),
                consent.getConsentId()
        );

        return response(entry, consent);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public ConsentDecisionResponse resolveInsertConflict(
            UUID operationId,
            String requestHash,
            DataIntegrityViolationException originalFailure) {

        var existing =
                history.findByOperationId(operationId.toString());

        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        throw originalFailure;
    }

    private ConsentDecisionResponse replay(
            ConsentHistoryEntity entry,
            String requestHash) {

        if (!entry.getRequestHash().equals(requestHash)) {
            throw conflict("IDEMPOTENCY_CONFLICT");
        }

        ConsentEntity consent = consents.findById(
                entry.getId().getConsentId()
        ).orElseThrow(() -> new IllegalStateException(
                "Consent history references a missing consent"
        ));

        return response(entry, consent);
    }

    private ConsentDecisionResponse response(
            ConsentHistoryEntity entry,
            ConsentEntity consent) {

        int version = entry.getId().getConsentVersion();

        return new ConsentDecisionResponse(
                UUID.fromString(entry.getOperationId()),
                UUID.fromString(consent.getConsentId()),
                UUID.fromString(consent.getApplicationId()),
                version,
                entry.getConsentStatus(),
                consent.getOrchestrationAppliedVersion() >= version
                        ? "APPLIED"
                        : "PENDING"
        );
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                code
        );
    }
}