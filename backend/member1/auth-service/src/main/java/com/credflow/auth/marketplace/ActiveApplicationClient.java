package com.credflow.auth.marketplace;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@Component
public class ActiveApplicationClient {
	private final String base;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	public ActiveApplicationClient(@Value("${credflow.clients.application-url:http://127.0.0.1:8081}") String base,
			ObjectMapper json) {
		this.base = base.replaceAll("/+$", "");
		this.json = json;
	}

	public void requireNoActive(Jwt jwt) {
		requireNoActive(jwt, "WORKING_CAPITAL");
	}

	public void requireNoActive(Jwt jwt, String purpose) {
		if (!java.util.Set.of("WORKING_CAPITAL", "PURCHASE_INVENTORY", "BUSINESS_EXPANSION").contains(purpose))
			throw new IllegalArgumentException("Invalid lending purpose.");
		boolean active;
		String reference;
		try {
			var response = http
					.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/applications/active?purpose=" + purpose))
							.timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + jwt.getTokenValue())
							.GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
				throw new IllegalStateException();
			var value = json.readTree(response.body());
			if (!value.path("active").isBoolean())
				throw new IllegalStateException();
			active = value.path("active").asBoolean();
			reference = value.path("applicationId").asText();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw unavailable();
		} catch (Exception ex) {
			throw unavailable();
		}
		if (active)
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"You already have an active application for this purpose. Existing application: " + reference);
	}

	private ResponseStatusException unavailable() {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				"We could not check your existing application. Please try again shortly.");
	}
}
