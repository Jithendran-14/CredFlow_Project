package com.credflow.consent.client;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
public class FinancialApplicationClient {

	private static final Set<String> OPEN_STATUSES = Set.of("DRAFT", "SUBMITTED", "ACTION_REQUIRED",
			"ELIGIBILITY_CHECKING", "READY_FOR_LENDER", "SUBMITTING", "UNDER_REVIEW", "INFORMATION_REQUIRED",
			"MANUAL_REVIEW", "APPROVED");

	private final RestClient http;
	private final ServiceTokenProvider tokens;
	private final String baseUrl;

	public FinancialApplicationClient(@Qualifier("consentInternalRestClient") RestClient http,
			ServiceTokenProvider tokens, @Value("${credflow.clients.application.base-url}") String baseUrl) {

		this.http = http;
		this.tokens = tokens;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Context(UUID applicationId, Integer applicationRevision, String sellerId, UUID marketplaceId,
			String currency, String status) {
	}

	public Context get(UUID applicationId) {

		Context context;

		try {
			String token = tokens.getToken();

			try {
				context = call(applicationId, token);

			} catch (HttpClientErrorException.Unauthorized exception) {
				tokens.invalidate(token);
				context = call(applicationId, tokens.getToken());
			}

		} catch (HttpClientErrorException.NotFound exception) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FINANCIAL_ACCESS_DENIED");

		} catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_CONTEXT_UNAVAILABLE");
		}

		if (context == null || !applicationId.equals(context.applicationId()) || context.applicationRevision() == null
				|| context.sellerId() == null || context.sellerId().isBlank() || context.marketplaceId() == null
				|| context.status() == null || context.currency() == null) {

			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_APPLICATION_CONTEXT");
		}

		if (!OPEN_STATUSES.contains(context.status())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FINANCIAL_ACCESS_DENIED");
		}

		if (!"INR".equals(context.currency())) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_FINANCIAL_CURRENCY");
		}

		return context;
	}

	private Context call(UUID applicationId, String token) {

		return http.get().uri(baseUrl + "/internal/v1/applications/" + applicationId + "/context")
				.headers(headers -> headers.setBearerAuth(token)).retrieve().body(Context.class);
	}
}