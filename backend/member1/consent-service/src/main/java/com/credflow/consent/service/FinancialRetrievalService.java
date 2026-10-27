package com.credflow.consent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.credflow.consent.client.FinancialApplicationClient;
import com.credflow.consent.client.FinancialProviderClient;
import com.credflow.consent.dto.FinancialDataModels.*;
import com.credflow.consent.service.FinancialRetrievalStore.Prepared;

import tools.jackson.databind.ObjectMapper;

@Service
public class FinancialRetrievalService {

    private final FinancialApplicationClient applications;
    private final FinancialProviderClient provider;
    private final FinancialRetrievalStore store;
    private final ObjectMapper objectMapper;

    public FinancialRetrievalService(
            FinancialApplicationClient applications,
            FinancialProviderClient provider,
            FinancialRetrievalStore store,
            ObjectMapper objectMapper) {

        this.applications = applications;
        this.provider = provider;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public Response retrieve(
            UUID applicationId,
            Request request,
            String idempotencyKey,
            String correlationHeader,
            Jwt jwt) {

        String clientId = requireMachine(jwt);
        UUID operationId = parseKey(idempotencyKey);

        String correlationId = correlationHeader == null
                ? UUID.randomUUID().toString()
                : correlationHeader;

        if (!correlationId.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw badRequest("INVALID_CORRELATION_ID");
        }

        var business = new TreeMap<String, Object>();
        business.put("applicationId", applicationId.toString());
        business.put("clientId", clientId);
        business.put("request", request);

        String requestHash = sha256(
                objectMapper.writeValueAsString(business)
        );

        var context = applications.get(applicationId);

        Prepared prepared = retryConflict(() -> store.prepare(
                applicationId,
                request,
                clientId,
                operationId,
                requestHash,
                correlationId,
                context
        ));

        if (prepared.job().cachedDataset() == null) {
            var job = prepared.job();

            var end = request.asOfDate().withDayOfMonth(1);
            var start = end.minusMonths(3);

            Dataset dataset = provider.retrieve(
                    new ProviderRequest(
                            job.providerRequestId(),
                            job.sellerFixtureId(),
                            "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY",
                            request.asOfDate(),
                            start,
                            end
                    ),
                    job.sourceProvider(),
                    job.correlationId()
            );

            String contentHash = sha256(
                    objectMapper.writeValueAsString(dataset)
            );

            // Recheck trusted application context after the provider call.
            var latestContext = applications.get(applicationId);

            prepared = retryConflict(() -> store.complete(
                    applicationId,
                    request,
                    clientId,
                    latestContext,
                    dataset,
                    contentHash
            ));
        }

        return response(applicationId, request, prepared);
    }

    private Response response(
            UUID applicationId,
            Request request,
            Prepared prepared) {

        Dataset dataset = prepared.job().cachedDataset();

        if (dataset == null) {
            throw new IllegalStateException(
                    "FINANCIAL_DATASET_NOT_SAVED"
            );
        }

        return new Response(
                applicationId,
                request.applicationRevision(),
                request.verificationId(),
                new IdentityVerification(
                        "VERIFIED",
                        prepared.grant().checkedAt()
                ),
                new FinancialConsent(
                        prepared.grant().financialConsentId(),
                        prepared.grant().financialConsentVersion(),
                        "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY",
                        "ACTIVE"
                ),
                dataset.sourceProvider(),
                dataset.dataReference(),
                dataset.dataVersion(),
                dataset.retrievedAt(),
                new FinancialData(
                        request.asOfDate(),
                        3,
                        dataset.periodStart(),
                        dataset.periodEndExclusive(),
                        dataset.currency(),
                        dataset.months(),
                        dataset.creditScoreBand()
                )
        );
    }

    public static String clientIdentity(Jwt jwt) {

        String clientId = jwt.getClaimAsString("client_id");
        String azp = jwt.getClaimAsString("azp");

        if (clientId != null && azp != null
                && !clientId.equals(azp)) {
            return null;
        }

        return azp != null ? azp : clientId;
    }

    private String requireMachine(Jwt jwt) {

        if (jwt == null
                || !"eligibility-service".equals(
                        clientIdentity(jwt))) {
            throw denied();
        }

        var roles = jwt.getClaimAsStringList("roles");

        if (roles == null
                || !roles.contains("ELIGIBILITY_SERVICE")) {
            throw denied();
        }

        Object scope = jwt.getClaim("scope");

        boolean allowed = false;

        if (scope instanceof String text) {
            allowed = Arrays.stream(text.trim().split("\\s+"))
                    .anyMatch("financial-data.read"::equals);

        } else if (scope instanceof Collection<?> values) {
            allowed = values.contains("financial-data.read");
        }

        if (!allowed) {
            throw denied();
        }

        return "eligibility-service";
    }

    private UUID parseKey(String value) {

        if (value == null || value.isBlank()) {
            throw badRequest("IDEMPOTENCY_KEY_REQUIRED");
        }

        try {
            return UUID.fromString(value);

        } catch (IllegalArgumentException exception) {
            throw badRequest("IDEMPOTENCY_KEY_MUST_BE_UUID");
        }
    }

    private <T> T retryConflict(Supplier<T> operation) {

        try {
            return operation.get();

        } catch (DuplicateKeyException exception) {
            // Retry only after the previous transaction rolled back.
            return operation.get();
        }
    }

    public static String sha256(String value) {

        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseStatusException denied() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "FINANCIAL_ACCESS_DENIED"
        );
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                reason
        );
    }
}