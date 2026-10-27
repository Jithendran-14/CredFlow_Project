package com.credflow.consent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.credflow.consent.dto.VerificationAcceptedResponse;
import com.credflow.consent.dto.VerificationRequest;
import com.credflow.consent.service.VerificationRequestService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/internal/verifications")
public class VerificationRequestController {

    private final VerificationRequestService service;

    public VerificationRequestController(
            VerificationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VerificationAcceptedResponse> accept(
            @Valid @RequestBody VerificationRequest request,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    value = "X-Correlation-ID",
                    required = false
            ) String correlationId) {

        VerificationAcceptedResponse response = service.accept(
                request,
                idempotencyKey,
                correlationId
        );

        return ResponseEntity.accepted().body(response);
    }
}