package com.credflow.consent.client;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
public class ApplicationOwnershipClient {

	private final RestClient http;
	private final ServiceTokenProvider tokens;
	private final String baseUrl;

	public ApplicationOwnershipClient(@Qualifier("consentInternalRestClient") RestClient http,
			ServiceTokenProvider tokens, @Value("${credflow.clients.application.base-url}") String baseUrl) {

		this.http = http;
		this.tokens = tokens;
		this.baseUrl = baseUrl;
	}

	public void requireOwnership(UUID applicationId, String sellerId) {

		if (sellerId == null || sellerId.isBlank()) {
			throw new AccessDeniedException("Authenticated seller identity is required");
		}

		OwnershipResponse response;

		try {
			String token = tokens.getToken();

			try {
				response = call(applicationId, sellerId, token);

			} catch (HttpClientErrorException.Unauthorized exception) {
				tokens.invalidate(token);

				response = call(applicationId, sellerId, tokens.getToken());
			}

		} catch (HttpClientErrorException.NotFound exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND");

		} catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"APPLICATION_OWNERSHIP_CHECK_UNAVAILABLE");
		}

		if (response == null || !applicationId.equals(response.applicationId()) || response.ownsApplication() == null) {

			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_APPLICATION_OWNERSHIP_RESPONSE");
		}

		if (!response.ownsApplication()) {
			throw new AccessDeniedException("Seller does not own this application");
		}
	}

	private OwnershipResponse call(UUID applicationId, String sellerId, String token) {

		return http.post().uri(baseUrl + "/internal/v1/applications/" + applicationId + "/ownership-check")
				.headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
				.body(new OwnershipRequest(sellerId)).retrieve().body(OwnershipResponse.class);
	}

	public record OwnershipRequest(String sellerId) {
	}

	public record OwnershipResponse(UUID applicationId, Boolean ownsApplication) {
	}

	public ApplicationContext getContext(UUID applicationId) {

		ApplicationContext response;

		try {
			String token = tokens.getToken();

			try {
				response = callContext(applicationId, token);

			} catch (HttpClientErrorException.Unauthorized exception) {
				tokens.invalidate(token);
				response = callContext(applicationId, tokens.getToken());
			}

		} catch (HttpClientErrorException.NotFound exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND");

		} catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_CONTEXT_UNAVAILABLE");
		}

		if (response == null || !applicationId.equals(response.applicationId()) || response.marketplaceId() == null) {

			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_APPLICATION_CONTEXT");
		}

		return response;
	}

	private ApplicationContext callContext(UUID applicationId, String token) {

		return http.get().uri(baseUrl + "/internal/v1/applications/" + applicationId + "/context")
				.headers(headers -> headers.setBearerAuth(token)).retrieve().body(ApplicationContext.class);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ApplicationContext(UUID applicationId, Integer applicationRevision, String sellerId,
			UUID marketplaceId) {
	}
}