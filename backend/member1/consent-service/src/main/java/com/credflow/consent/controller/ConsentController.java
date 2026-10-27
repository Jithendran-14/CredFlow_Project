package com.credflow.consent.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.credflow.consent.dto.ConsentDecisionRequest;
import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.service.ConsentDecisionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/applications")
public class ConsentController {

    private final ConsentDecisionService service;

    public ConsentController(ConsentDecisionService service) {
        this.service = service;
    }

    @PostMapping("/{applicationId}/consents")
    public ResponseEntity<ConsentDecisionResponse> record(
            @PathVariable("applicationId") UUID applicationId,
            @Valid @RequestBody ConsentDecisionRequest request,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    value = "X-Correlation-ID",
                    required = false
            ) String correlationId,
            @AuthenticationPrincipal Jwt jwt) {

        var response = service.record(
                applicationId,
                request,
                idempotencyKey,
                correlationId,
                jwt
        );

        return ResponseEntity.accepted().body(response);
    }
}