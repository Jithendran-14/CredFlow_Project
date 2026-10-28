package com.credflow.eligibility.api;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Exact machine identity plus a configured scope. No grant is invented while
 * Auth is pending.
 */
@Component("eligibilityMachineAccess")
public class EligibilityMachineAccess {
	private final String client, scope;

	public EligibilityMachineAccess(@Value("${credflow.eligibility.caller-client:orchestration-service}") String client,
			@Value("${credflow.eligibility.evaluation-scope:}") String scope) {
		this.client = client;
		this.scope = scope;
	}

	public boolean allowed(Authentication auth) {
		if (client == null || client.isBlank() || scope == null || scope.isBlank()
				|| !(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated())
			return false;
		var claims = token.getToken().getClaims();
		Object azp = claims.get("azp"), clientId = claims.get("client_id");
		if (azp == null && clientId == null)
			return false;
		if (azp != null && !client.equals(azp) || clientId != null && !client.equals(clientId))
			return false;
		Set<String> scopes = new HashSet<>();
		for (String field : List.of("scope", "scp")) {
			Object value = claims.get(field);
			if (value instanceof String text)
				scopes.addAll(Arrays.asList(text.split("\\s+")));
			else if (value instanceof Collection<?> items)
				for (Object item : items)
					if (item instanceof String text)
						scopes.add(text);
		}
		return scopes.contains(scope);
	}

	public String clientId() {
		return client;
	}
}
