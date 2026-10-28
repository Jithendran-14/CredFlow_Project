package com.credflow.simulator.verification.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Dedicated service authority; align its value with the team's issuer before
 * integration.
 */
@Component("verificationAccess")
public class VerificationAccess {
	private final String authority;

	public VerificationAccess(
			@Value("${credflow.verification.required-authority:ROLE_VERIFICATION_SERVICE}") String authority) {
		if (authority == null || authority.isBlank())
			throw new IllegalArgumentException("Verification authority is required");
		this.authority = authority;
	}

	public boolean allowed(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
	}
}
