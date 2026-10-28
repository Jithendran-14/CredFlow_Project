package com.credflow.consent.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.ConsentDecisionRequest;
import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.entity.ConsentEntity;
import com.credflow.consent.entity.ConsentHistoryEntity;
import com.credflow.consent.entity.ConsentStatus;
import com.credflow.consent.repository.ConsentHistoryRepository;
import com.credflow.consent.repository.ConsentRepository;
import com.credflow.consent.entity.ConsentDeliveryEntity;
import com.credflow.consent.repository.ConsentDeliveryRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConsentTransactionService {

	private final ConsentRepository consents;
	private final ConsentHistoryRepository history;
	private final ObjectMapper objectMapper;
	private final ConsentDeliveryRepository deliveries;

	public ConsentTransactionService(ConsentRepository consents, ConsentHistoryRepository history,
			ConsentDeliveryRepository deliveries, ObjectMapper objectMapper) {

		this.consents = consents;
		this.history = history;
		this.deliveries = deliveries;
		this.objectMapper = objectMapper;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ConsentDecisionResponse recordInitialDecision(UUID applicationId, String sellerId, String actorSubject,
			UUID operationId, String correlationId, String requestHash, ConsentDecisionRequest request) {

		var existingOperation = history.findByOperationId(operationId.toString());

		if (existingOperation.isPresent()) {
			return replay(existingOperation.get(), requestHash);
		}

		if (request.expectedConsentVersion() != 0) {
			throw conflict("INITIAL_CONSENT_VERSION_MUST_BE_ZERO");
		}

		if (consents.findByApplicationId(applicationId.toString()).isPresent()) {
			throw conflict("CONSENT_ALREADY_RECORDED");
		}

		UUID consentId = UUID.randomUUID();

		ConsentStatus status = ConsentStatus.valueOf(request.decision().name());

		Map<String, Object> payload = new TreeMap<>();

		payload.put("operationId", operationId.toString());
		payload.put("consentId", consentId.toString());
		payload.put("consentVersion", 1);
		payload.put("status", status.name());

		payload.put("scopes", request.scopes().stream().map(Enum::name).sorted().toList());

		payload.put("sourceMarketplaceIds",
				request.sourceMarketplaceIds().stream().map(UUID::toString).sorted().toList());

		payload.put("recipientLenderIds", request.recipientLenderIds().stream().map(UUID::toString).sorted().toList());

		payload.put("purpose", "Evaluate and route this loan application.");

		payload.put("validityPolicy", "APPLICATION_OPEN");
		payload.put("noticeVersion", request.noticeVersion());

		payload.put("originActor", Map.of("type", "HUMAN", "subject", actorSubject));

		if (status == ConsentStatus.GRANTED) {
			payload.put("grantedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
		}

		String payloadJson = objectMapper.writeValueAsString(payload);

		ConsentEntity consent = new ConsentEntity(consentId, applicationId, sellerId, status, payloadJson);

		// Flush the parent first to satisfy the history foreign key.
		consents.saveAndFlush(consent);

		ConsentHistoryEntity entry = new ConsentHistoryEntity(consentId.toString(), 1, operationId, status,
				actorSubject, correlationId, requestHash, payloadJson);

		history.saveAndFlush(entry);

		// The history must exist before inserting its delivery record.
		deliveries.saveAndFlush(ConsentDeliveryEntity.pending(operationId));

		return response(entry, consent);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public ConsentDecisionResponse resolveInsertConflict(UUID applicationId, UUID operationId, String requestHash,
			DataIntegrityViolationException originalFailure) {

		var existingOperation = history.findByOperationId(operationId.toString());

		if (existingOperation.isPresent()) {
			return replay(existingOperation.get(), requestHash);
		}

		if (consents.findByApplicationId(applicationId.toString()).isPresent()) {
			throw conflict("CONSENT_ALREADY_RECORDED");
		}

		throw originalFailure;
	}

	private ConsentDecisionResponse replay(ConsentHistoryEntity entry, String requestHash) {

		if (!entry.getRequestHash().equals(requestHash)) {
			throw conflict("IDEMPOTENCY_CONFLICT");
		}

		ConsentEntity consent = consents.findById(entry.getId().getConsentId())
				.orElseThrow(() -> new IllegalStateException("Consent history references a missing consent"));

		return response(entry, consent);
	}

	private ConsentDecisionResponse response(ConsentHistoryEntity entry, ConsentEntity consent) {

		int version = entry.getId().getConsentVersion();

		String coordination = consent.getOrchestrationAppliedVersion() >= version ? "APPLIED" : "PENDING";

		return new ConsentDecisionResponse(UUID.fromString(entry.getOperationId()),
				UUID.fromString(entry.getId().getConsentId()), UUID.fromString(consent.getApplicationId()), version,
				entry.getConsentStatus(), coordination);
	}

	private ResponseStatusException conflict(String code) {
		return new ResponseStatusException(HttpStatus.CONFLICT, code);
	}
}