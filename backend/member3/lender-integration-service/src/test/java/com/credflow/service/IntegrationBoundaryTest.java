package com.credflow.service;

import com.credflow.entity.IntegrationDispatch;
import com.credflow.platform.Platform;
import com.credflow.repository.IntegrationRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.HttpClientErrorException;

class IntegrationBoundaryTest {

    @org.junit.jupiter.api.Test
    void contractsRemainCompatible() {
        Platform p = new Platform(new StandardEnvironment(), new ObjectMapper().findAndRegisterModules(), new DataSourceTransactionManager());
        LenderIntegrationService engine = configured(p);
        ObjectNode request = p.json(Map.of()).deepCopy();
        for (String key : List.of("submissionId", "applicationId", "productId", "financialSnapshotId", "consentId", "eligibilityResultId", "verificationId")) {
            request.put(key, UUID.randomUUID().toString());
        }
        request.put("lenderId", engine.bankA);
        request.put("applicationRevision", 1);
        request.put("consentVersion", 1);
        request.put("sellerSubject", "seller-123");
        request.put("marketplaceSellerId", "MKT-123");
        request.put("registeredBusinessName", "Example");
        request.put("loanPurpose", "WORKING_CAPITAL");
        request.put("requestedAmount", "1000.00");
        request.put("currency", "INR");
        request.set("originActor", p.json(new Platform.Actor("HUMAN", "seller-123", null)));
        engine.validateSubmission(request);
        IntegrationDispatch dispatch = new IntegrationDispatch();
        dispatch.setRequestJson(p.write(request));
        dispatch.setLenderId(engine.bankA);
        JsonNode bank = engine.wireRequest(dispatch);
        check(bank.path("submissionReference").equals(request.path("submissionId")) && bank.path("requestedFacility").asText().equals("1000.00") && !bank.has("caseReference"), "Bank A request mapping");
        dispatch.setLenderId(engine.nbfcB);
        JsonNode nbfc = engine.wireRequest(dispatch);
        check(nbfc.path("caseReference").equals(request.path("submissionId")) && nbfc.path("requestedLimit").asText().equals("1000.00") && !nbfc.has("submissionReference"), "NBFC request mapping");
        ObjectNode numeric = request.deepCopy();
        numeric.put("requestedAmount", 1000.00);
        rejects(() -> engine.validateSubmission(numeric), "numeric money must be rejected");
        ObjectNode zero = request.deepCopy();
        zero.put("requestedAmount", "0.00");
        rejects(() -> engine.validateSubmission(zero), "zero request must be rejected");
        ObjectNode callback = p.json(Map.of()).deepCopy();
        callback.put("eventId", UUID.randomUUID().toString());
        callback.put("applicationId", request.path("applicationId").asText());
        callback.put("submissionId", request.path("submissionId").asText());
        callback.put("lenderSequence", 1);
        callback.put("applicationRevision", 1);
        callback.put("lenderId", engine.bankA);
        callback.put("lenderApplicationId", "BA-123");
        callback.put("occurredAt", Instant.now().toString());
        callback.put("eventType", "loan.offer.acknowledged");
        callback.set("data", p.json(Map.of("offerId", UUID.randomUUID().toString(), "acceptanceId", UUID.randomUUID().toString(), "lenderAcknowledgementStatus", "ACKNOWLEDGED")));
        engine.validateCallback(engine.bankA, callback);
        rejects(() -> engine.validateCallback(engine.nbfcB, callback), "callback lender binding");
        ObjectNode badType = callback.deepCopy();
        badType.put("eventType", "untrusted.topic");
        rejects(() -> engine.validateCallback(engine.bankA, badType), "callback topic allowlist");
        ObjectNode questions = p.json(Map.of("informationRequestId", UUID.randomUUID().toString(), "questions", List.of(Map.of("questionId", "Q1", "prompt", "One"), Map.of("questionId", "Q1", "prompt", "Two")))).deepCopy();
        rejects(() -> engine.validateCommand("REQUEST_INFORMATION", questions), "duplicate question binding");
        IntegrationDispatch lookup = new IntegrationDispatch();
        lookup.setLenderId(engine.bankA);
        lookup.setSubmissionId(UUID.randomUUID());
        lookup.setApplicationId(UUID.randomUUID());
        Platform emptyLookup = new Platform(new StandardEnvironment(), new ObjectMapper(), new DataSourceTransactionManager()) {

            @Override
            public JsonNode call(String registration, HttpMethod method, String url, Object body, String key, String correlation) {
                return null;
            }
        };
        rejectsState(() -> configured(emptyLookup).lookup(lookup), "empty lookup is not absence");
        Platform absentLookup = new Platform(new StandardEnvironment(), new ObjectMapper(), new DataSourceTransactionManager()) {

            @Override
            public JsonNode call(String registration, HttpMethod method, String url, Object body, String key, String correlation) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Absent", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
            }
        };
        check(configured(absentLookup).lookup(lookup) == null, "only explicit 404 is absence");
        check(p.hash(p.json(Map.of("a", 1, "b", 2))).equals(p.hash(p.json(Map.of("b", 2, "a", 1)))), "canonical hash property order");
    }

    static LenderIntegrationService configured(Platform p) {
        LenderIntegrationService engine = new LenderIntegrationService(p, new IntegrationRepository(org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class)));
        engine.bankA = "11111111-1111-4111-8111-111111111111";
        engine.nbfcB = "22222222-2222-4222-8222-222222222222";
        engine.bankAUrl = "http://localhost:8091";
        engine.nbfcBUrl = "http://localhost:8092";
        return engine;
    }

    static void rejects(Runnable action, String name) {
        try {
            action.run();
            throw new AssertionError(name);
        } catch (IllegalArgumentException expected) {
        }
    }

    static void rejectsState(Runnable action, String name) {
        try {
            action.run();
            throw new AssertionError(name);
        } catch (IllegalStateException expected) {
        }
    }

    static void check(boolean valid, String name) {
        if (!valid) {
            throw new AssertionError(name);
        }
    }
}
