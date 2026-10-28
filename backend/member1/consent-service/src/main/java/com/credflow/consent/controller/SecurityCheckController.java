package com.credflow.consent.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/security")
public class SecurityCheckController {

	@GetMapping("/check")
	public SecurityCheckResponse check(@AuthenticationPrincipal Jwt jwt) {

		return new SecurityCheckResponse("consent-service", jwt.getSubject(), jwt.getClaimAsStringList("roles"),
				jwt.getClaimAsString("client_id"), jwt.getClaimAsString("scope"));
	}

	public record SecurityCheckResponse(String service, String subject, List<String> roles, String clientId,
			String scope) {
	}

	@GetMapping("/machine-check")
	public SecurityCheckResponse checkMachine(@AuthenticationPrincipal Jwt jwt) {

		return new SecurityCheckResponse("consent-service", jwt.getSubject(), jwt.getClaimAsStringList("roles"),
				jwt.getClaimAsString("client_id"), jwt.getClaimAsString("scope"));
	}
}