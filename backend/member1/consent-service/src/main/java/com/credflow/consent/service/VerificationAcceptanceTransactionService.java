package com.credflow.consent.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.VerificationAcceptedResponse;
import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.entity.VerificationRequestEntity;
import com.credflow.consent.repository.VerificationRequestRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class VerificationAcceptanceTransactionService {

    private final VerificationRequestRepository repository;
    private final ObjectMapper objectMapper;

    public VerificationAcceptanceTransactionService(
            VerificationRequestRepository repository,
            ObjectMapper objectMapper) {

        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerificationAcceptedResponse accept(
            VerificationRequest request,
            String requestHash) {

        var existing = repository.findById(
                request.verificationId().toString());

        if (existing.isPresent()) {
            return replayResponse(existing.get(), requestHash);
        }

        String payload = objectMapper.writeValueAsString(request);

        VerificationRequestEntity entity =
                new VerificationRequestEntity(
                        request.verificationId(),
                        request.applicationId(),
                        request.applicationRevision(),
                        request.consentId(),
                        request.consentVersion(),
                        requestHash,
                        payload,
                        request.correlationId(),
                        request.causationId()
                );

        VerificationRequestEntity saved =
                repository.saveAndFlush(entity);

        return response(saved);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public Optional<VerificationAcceptedResponse> findReplay(
            UUID verificationId,
            String requestHash) {

        return repository.findById(verificationId.toString())
                .map(entity -> replayResponse(entity, requestHash));
    }

    private VerificationAcceptedResponse replayResponse(
            VerificationRequestEntity entity,
            String requestHash) {

        if (!entity.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "VERIFICATION_REQUEST_CONFLICT"
            );
        }

        return response(entity);
    }

    private VerificationAcceptedResponse response(
            VerificationRequestEntity entity) {

        return new VerificationAcceptedResponse(
                UUID.fromString(entity.getVerificationId()),
                UUID.fromString(entity.getApplicationId()),
                entity.getApplicationRevision(),
                "ACCEPTED"
        );
    }
}