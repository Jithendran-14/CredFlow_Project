package com.credflow.simulator.financial;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class FinancialMockController {
	private final FinancialMockService service;

	public FinancialMockController(FinancialMockService service) {
		this.service = service;
	}

	@PostMapping("/internal/v1/financial-data/retrievals")
	@PreAuthorize("hasRole('VERIFICATION_SERVICE')")
	public JsonNode retrieve(@RequestBody FinancialMockService.Request request,
			@RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal Jwt jwt) {
		if (!"consent-verification-service".equals(jwt.getClaimAsString("client_id"))
				|| jwt.hasClaim("azp") && !"consent-verification-service".equals(jwt.getClaimAsString("azp")))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		return service.retrieve(request, key);
	}
}
