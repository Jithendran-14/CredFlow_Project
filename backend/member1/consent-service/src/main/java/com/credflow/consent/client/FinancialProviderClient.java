package com.credflow.consent.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.dto.FinancialDataModels.Dataset;
import com.credflow.consent.dto.FinancialDataModels.ProviderRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class FinancialProviderClient {

    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper objectMapper;
    private final String providerUrl;

    public FinancialProviderClient(
            @Qualifier("consentInternalRestClient")
            RestClient http,
            ServiceTokenProvider tokens,
            ObjectMapper objectMapper,
            @Value("${credflow.clients.financial.provider-url:}")
            String providerUrl) {

        this.http = http;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.providerUrl = providerUrl;
    }

    public Dataset retrieve(
            ProviderRequest request,
            String sourceProvider,
            String correlationId) {

        if (providerUrl.isBlank()) {
            throw unavailable("FINANCIAL_PROVIDER_NOT_CONFIGURED");
        }

        String json;

        try {
            String token = tokens.getToken();

            try {
                json = call(request, correlationId, token);

            } catch (HttpClientErrorException.Unauthorized exception) {
                tokens.invalidate(token);
                json = call(
                        request,
                        correlationId,
                        tokens.getToken()
                );
            }

        } catch (RestClientException exception) {
            throw unavailable("FINANCIAL_PROVIDER_UNAVAILABLE");
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            if (!request.requestId().toString().equals(
                        root.path("requestId").asText())
                    || !request.sellerFixtureId().equals(
                        root.path("sellerFixtureId").asText())
                    || !"SUCCESS".equals(
                        root.path("status").asText())) {

                throw invalid();
            }

            JsonNode rawDataset = root.path("dataset");

            if (!rawDataset.path("dataVersion").isIntegralNumber()
                    || !rawDataset.path("dataVersion")
                            .canConvertToLong()) {

                throw invalid();
            }

            JsonNode months = rawDataset.path("months");

            if (!months.isArray() || months.size() != 3) {
                throw invalid();
            }

            for (JsonNode month : months) {
                String status = month.path("status").asText();

                if ("AVAILABLE".equals(status)) {
                    requireMoney(month.path("grossSalesAmount"));
                    requireMoney(month.path("returnsAmount"));
                    requireMoney(month.path("payoutAmount"));

                    if (!month.path("orderCount").isIntegralNumber()
                            || !month.path("orderCount")
                                    .canConvertToLong()
                            || !month.path("returnedOrderCount")
                                    .isIntegralNumber()
                            || !month.path("returnedOrderCount")
                                    .canConvertToLong()) {

                        throw invalid();
                    }

                } else if ("MISSING".equals(status)) {
                    if (month.hasNonNull("grossSalesAmount")
                            || month.hasNonNull("returnsAmount")
                            || month.hasNonNull("payoutAmount")
                            || month.hasNonNull("orderCount")
                            || month.hasNonNull("returnedOrderCount")) {

                        throw invalid();
                    }

                } else {
                    throw invalid();
                }
            }

            Dataset dataset = objectMapper.treeToValue(
                    rawDataset,
                    Dataset.class
            );

            validate(dataset, request, sourceProvider);

            return new Dataset(
                    dataset.sourceProvider(),
                    dataset.dataReference(),
                    dataset.dataVersion(),
                    dataset.retrievedAt()
                            .withOffsetSameInstant(ZoneOffset.UTC),
                    dataset.currency(),
                    dataset.periodStart(),
                    dataset.periodEndExclusive(),
                    java.util.List.copyOf(dataset.months()),
                    dataset.creditScoreBand()==null?"NOT_AVAILABLE":dataset.creditScoreBand()
            );

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private String call(
            ProviderRequest request,
            String correlationId,
            String token) {

        return http.post()
                .uri(providerUrl)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set(
                            "Idempotency-Key",
                            request.requestId().toString()
                    );
                    headers.set("X-Correlation-ID", correlationId);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
    }

    private void validate(
            Dataset dataset,
            ProviderRequest request,
            String sourceProvider) {

        if (dataset == null
                || !sourceProvider.equals(dataset.sourceProvider())
                || dataset.dataReference() == null
                || dataset.dataReference().isBlank()
                || dataset.dataReference().length() > 200
                || dataset.dataVersion() == null
                || dataset.dataVersion() < 1
                || dataset.retrievedAt() == null
                || dataset.retrievedAt().isAfter(
                        OffsetDateTime.now(ZoneOffset.UTC)
                                .plusMinutes(1))
                || !"INR".equals(dataset.currency())
                || !request.periodStart().equals(
                        dataset.periodStart())
                || !request.periodEndExclusive().equals(
                        dataset.periodEndExclusive())
                || dataset.months() == null
                || dataset.months().size() != 3) {

            throw invalid();
        }

        YearMonth first = YearMonth.from(request.periodStart());

        for (int index = 0; index < 3; index++) {
            var month = dataset.months().get(index);

            if (month == null
                    || !first.plusMonths(index).toString().equals(
                            month.month())) {

                throw invalid();
            }

            if ("AVAILABLE".equals(month.status())) {
                if (month.orderCount() == null
                        || month.returnedOrderCount() == null
                        || month.orderCount() < 0
                        || month.returnedOrderCount() < 0
                        || month.returnedOrderCount()
                                > month.orderCount()
                        || new BigDecimal(month.returnsAmount())
                                .compareTo(new BigDecimal(
                                        month.grossSalesAmount())) > 0) {

                    throw invalid();
                }
            }
        }
    }

    private void requireMoney(JsonNode value) {

        if (!value.isTextual()
                || !value.asText().matches(
                        "(0|[1-9][0-9]{0,15})\\.[0-9]{2}")) {

            throw invalid();
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "INVALID_FINANCIAL_PROVIDER_RESPONSE"
        );
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                reason
        );
    }
}