package com.credflow.consent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.credflow.consent.dto.FinancialConsentRequest;
import com.credflow.consent.dto.FinancialConsentResponse;
import com.credflow.consent.service.FinancialConsentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/applications")
public class FinancialConsentController {

    private final FinancialConsentService service;

    public FinancialConsentController(
            FinancialConsentService service) {

        this.service = service;
    }

    @PostMapping("/{applicationId}/financial-consents")
    public ResponseEntity<FinancialConsentResponse> grant(
            @PathVariable("applicationId") UUID applicationId,
            @Valid @RequestBody FinancialConsentRequest request,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    value = "X-Correlation-ID",
                    required = false
            ) String correlationId,
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.grant(
                        applicationId,
                        request,
                        idempotencyKey,
                        correlationId,
                        jwt
                )
        );
    }
}