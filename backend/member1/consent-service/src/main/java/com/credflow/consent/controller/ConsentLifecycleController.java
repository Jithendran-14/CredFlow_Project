package com.credflow.consent.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.credflow.consent.dto.ConsentDecisionRequest;
import com.credflow.consent.dto.ConsentDecisionResponse;
import com.credflow.consent.dto.FinancialWithdrawalRequest;
import com.credflow.consent.dto.FinancialWithdrawalResponse;
import com.credflow.consent.service.ConsentLifecycleService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/applications")
public class ConsentLifecycleController {

	private final ConsentLifecycleService service;

	public ConsentLifecycleController(ConsentLifecycleService service) {
		this.service = service;
	}

	@PutMapping("/{applicationId}/consents")
	public ResponseEntity<ConsentDecisionResponse> update(@PathVariable("applicationId") UUID applicationId,
			@Valid @RequestBody ConsentDecisionRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
			@AuthenticationPrincipal Jwt jwt) {

		return ResponseEntity.accepted().body(service.update(applicationId, request, key, correlation, jwt));
	}

	@PostMapping("/{applicationId}/financial-consents/revoke")
	public ResponseEntity<FinancialWithdrawalResponse> withdraw(@PathVariable("applicationId") UUID applicationId,
			@Valid @RequestBody FinancialWithdrawalRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
			@AuthenticationPrincipal Jwt jwt) {

		return ResponseEntity.ok(service.withdraw(applicationId, request, key, correlation, jwt));
	}
}