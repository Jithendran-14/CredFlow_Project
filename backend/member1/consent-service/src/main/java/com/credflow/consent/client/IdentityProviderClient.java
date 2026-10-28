package com.credflow.consent.client;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.credflow.consent.dto.IdentityCheckRequest;
import com.credflow.consent.dto.IdentityCheckResponse;

@Component
public class IdentityProviderClient {

	private final RestClient http;
	private final ServiceTokenProvider tokens;
	private final String baseUrl;

	public IdentityProviderClient(@Qualifier("consentInternalRestClient") RestClient http, ServiceTokenProvider tokens,
			@Value("${credflow.clients.identity.base-url}") String baseUrl) {

		this.http = http;
		this.tokens = tokens;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	public IdentityCheckResponse check(IdentityCheckRequest request, String correlationId) {

		validateRequest(request, correlationId);

		for (int attempt = 0; attempt < 2; attempt++) {
			String token = tokens.getToken();

			try {
				var response = http.post().uri(baseUrl + "/internal/v1/verification/checks").headers(headers -> {
					headers.setBearerAuth(token);

					headers.set("Idempotency-Key", request.verificationRequestId().toString());

					headers.set("X-Correlation-ID", correlationId);
				}).contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
						.toEntity(IdentityCheckResponse.class);

				if (response.getStatusCode().value() != 200) {
					throw invalidResponse();
				}

				IdentityCheckResponse body = response.getBody();

				validateResponse(request, body);

				return body;

			} catch (HttpClientErrorException exception) {
				if (exception.getStatusCode().value() == 401 && attempt == 0) {

					tokens.invalidate(token);
					continue;
				}

				throw exception;
			}
		}

		throw new IllegalStateException("IDENTITY_PROVIDER_AUTHENTICATION_FAILED");
	}

	private void validateRequest(IdentityCheckRequest request, String correlationId) {

		if (request == null || request.verificationRequestId() == null || request.applicationId() == null
				|| request.applicationRevision() < 1 || request.fixtureIdentityId() == null
				|| request.fixtureIdentityId().isBlank() || request.fixtureIdentityId().length() > 100
				|| correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,100}")) {

			throw new IllegalArgumentException("INVALID_IDENTITY_CHECK_REQUEST");
		}
	}

	private void validateResponse(IdentityCheckRequest request, IdentityCheckResponse response) {

		if (response == null || !Objects.equals(request.verificationRequestId(), response.verificationRequestId())
				|| !Objects.equals(request.applicationId(), response.applicationId())
				|| !Objects.equals(Integer.valueOf(request.applicationRevision()), response.applicationRevision())
				|| !Objects.equals(request.fixtureIdentityId(), response.fixtureIdentityId())
				|| response.status() == null || response.checkedAt() == null) {

			throw invalidResponse();
		}

		boolean requiresReason = response.status() == IdentityCheckResponse.Status.FAILED
				|| response.status() == IdentityCheckResponse.Status.ACTION_REQUIRED;

		if (requiresReason && (response.reasonCode() == null || response.reasonCode().isBlank())) {

			throw invalidResponse();
		}

		if (response.reasonCode() != null && response.reasonCode().length() > 100) {

			throw invalidResponse();
		}

		if (response.providerReference() != null && response.providerReference().length() > 255) {

			throw invalidResponse();
		}
	}

	private IllegalStateException invalidResponse() {
		return new IllegalStateException("INVALID_IDENTITY_PROVIDER_RESPONSE");
	}
}