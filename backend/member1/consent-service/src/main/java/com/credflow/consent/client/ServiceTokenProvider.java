package com.credflow.consent.client;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ServiceTokenProvider {

    private final RestClient http;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private CachedToken cached;

    public ServiceTokenProvider(
            @Qualifier("consentInternalRestClient") RestClient http,
            @Value("${credflow.clients.auth.token-url}") String tokenUrl,
            @Value("${credflow.clients.auth.client-id}") String clientId,
            @Value("${credflow.clients.auth.client-secret}") String clientSecret) {

        this.http = http;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String getToken() {

        if (cached != null
                && cached.refreshAt().isAfter(Instant.now())) {
            return cached.value();
        }

        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw unavailable("SERVICE_AUTHENTICATION_NOT_CONFIGURED");
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");

        Instant requestedAt = Instant.now();

        TokenResponse response;

        try {
            response = http.post()
                    .uri(tokenUrl)
                    .headers(headers ->
                            headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

        } catch (RestClientException exception) {
            throw unavailable("AUTH_SERVICE_UNAVAILABLE");
        }

        if (response == null
                || response.access_token() == null
                || response.access_token().isBlank()
                || !"Bearer".equalsIgnoreCase(response.token_type())
                || response.expires_in() == null
                || response.expires_in() <= 30) {

            throw unavailable("INVALID_SERVICE_TOKEN_RESPONSE");
        }

        cached = new CachedToken(
                response.access_token(),
                requestedAt.plusSeconds(response.expires_in() - 20)
        );

        return cached.value();
    }

    public synchronized void invalidate(String token) {

        if (cached != null && cached.value().equals(token)) {
            cached = null;
        }
    }

    private ResponseStatusException unavailable(String code) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                code
        );
    }

    private record CachedToken(
            String value,
            Instant refreshAt
    ) {
    }

    public record TokenResponse(
            String access_token,
            String token_type,
            Long expires_in,
            String scope
    ) {
    }
}