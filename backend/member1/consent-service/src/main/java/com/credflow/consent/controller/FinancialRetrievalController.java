package com.credflow.consent.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.credflow.consent.dto.FinancialDataModels.Request;
import com.credflow.consent.dto.FinancialDataModels.Response;
import com.credflow.consent.service.FinancialRetrievalService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/v1/applications")
public class FinancialRetrievalController {

    private final FinancialRetrievalService service;

    public FinancialRetrievalController(
            FinancialRetrievalService service) {

        this.service = service;
    }

    @PostMapping("/{applicationId}/financial-data/retrievals")
    public ResponseEntity<Response> retrieve(
            @PathVariable("applicationId") UUID applicationId,
            @Valid @RequestBody Request request,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    value = "X-Correlation-ID",
                    required = false
            ) String correlationId,
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.retrieve(
                        applicationId,
                        request,
                        idempotencyKey,
                        correlationId,
                        jwt
                ));
    }
}