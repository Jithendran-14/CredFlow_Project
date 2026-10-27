package com.credflow.consent.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

@Component
public class OrchestrationConsentClient {

    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OrchestrationConsentClient(
            @Qualifier("consentInternalRestClient") RestClient http,
            ServiceTokenProvider tokens,
            ObjectMapper objectMapper,
            @Value("${credflow.clients.orchestration.base-url}")
            String baseUrl) {

        this.http = http;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void apply(
            String applicationId,
            String operationId,
            String correlationId,
            String payload) {

        for (int attempt = 0; attempt < 2; attempt++) {
            String token = tokens.getToken();

            try {
                var response = http.post()
                        .uri(
                                baseUrl
                                + "/api/v1/internal/applications/"
                                + applicationId
                                + "/consent-operations"
                        )
                        .headers(headers -> {
                            headers.setBearerAuth(token);
                            headers.set(
                                    "Idempotency-Key",
                                    operationId
                            );
                            headers.set(
                                    "X-Correlation-ID",
                                    correlationId
                            );
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.readTree(payload))
                        .retrieve()
                        .toEntity(String.class);

                if (response.getStatusCode().value() != 200
                        || response.getBody() == null) {
                    throw new IllegalStateException(
                            "INVALID_ORCHESTRATION_ACKNOWLEDGEMENT"
                    );
                }

                var acknowledgement =
                        objectMapper.readTree(response.getBody());

                boolean matches =
                        operationId.equals(
                                acknowledgement.path("operationId")
                                        .asText()
                        )
                        && "APPLIED".equals(
                                acknowledgement.path("status")
                                        .asText()
                        )
                        && applicationId.equals(
                                acknowledgement.path("workflow")
                                        .path("applicationId")
                                        .asText()
                        );

                if (!matches) {
                    throw new IllegalStateException(
                            "INVALID_ORCHESTRATION_ACKNOWLEDGEMENT"
                    );
                }

                return;

            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() == 401
                        && attempt == 0) {

                    tokens.invalidate(token);
                    continue;
                }

                throw exception;
            }
        }

        throw new IllegalStateException(
                "ORCHESTRATION_AUTHENTICATION_FAILED"
        );
    }
}