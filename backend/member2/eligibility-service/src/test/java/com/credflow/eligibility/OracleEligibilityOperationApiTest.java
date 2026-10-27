package com.credflow.eligibility;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"credflow.eligibility.evaluation-scope=test.evaluate","credflow.eligibility.publisher-enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORACLE_FINANCIAL_TESTS",matches="true")
class OracleEligibilityOperationApiTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired EligibilityOperationStore operations;
    @MockitoBean JwtDecoder decoder;
    final List<UUID> cleanup=new ArrayList<>();
    static final String PATH="/internal/v1/eligibility/evaluations";
    ObjectNode body(){UUID id=UUID.randomUUID();cleanup.add(id);return json.createObjectNode()
        .put("evaluationRequestId",id.toString()).put("applicationId",UUID.randomUUID().toString()).put("applicationRevision",1)
        .put("verificationId",UUID.randomUUID().toString()).put("asOfDate","2026-10-15").put("authorisedRetrievalReference","test-authorised-source");}
    MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request){return request.with(jwt().jwt(b->b.claim("azp","orchestration-service").claim("scope","test.evaluate")));}
    MockHttpServletRequestBuilder postBody(ObjectNode body){return authorised(post(PATH)).contentType("application/json").header("Idempotency-Key",body.path("evaluationRequestId").asText()).content(body.toString());}
    JsonNode submit(ObjectNode body)throws Exception{return json.readTree(mvc.perform(postBody(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());}
    @AfterEach void clean(){for(UUID id:cleanup)jdbc.update("DELETE FROM eligibility_operation WHERE evaluation_request_id=?",id.toString());}
    @Test void acceptedRequestIsDurablePendingAndStatusDoesNotLeakAuthority()throws Exception{
        var body=body();var receipt=submit(body);String id=receipt.path("operationId").asText();
        assertThat(receipt.path("statusUrl").asText()).isEqualTo("/api/v1/operations/"+id);
        assertThat(new EligibilityOperationStore(new JdbcTestAccess(jdbc)).find(UUID.fromString(id),"orchestration-service")).isPresent();
        mvc.perform(authorised(get(receipt.path("statusUrl").asText()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.applicationId").value(body.path("applicationId").asText()))
            .andExpect(jsonPath("$.authorisedRetrievalReference").doesNotExist()).andExpect(jsonPath("$.eligibilityResultId").doesNotExist());
    }
    @Test void exactRetryReusesOriginalReceipt()throws Exception{
        var body=body();var first=submit(body);var second=submit(body);assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eligibility_operation WHERE evaluation_request_id=?",Integer.class,body.path("evaluationRequestId").asText())).isEqualTo(1);
    }
    @Test void changedReferenceUnderSameRequestIdConflicts()throws Exception{
        var body=body();submit(body);body.put("authorisedRetrievalReference","different-source");
        mvc.perform(postBody(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVALUATION_INPUT_CONFLICT"));
    }
    @Test void mismatchedHeaderConflictsBeforeStorage()throws Exception{
        var body=body();mvc.perform(authorised(post(PATH)).contentType("application/json").header("Idempotency-Key",UUID.randomUUID().toString()).content(body.toString())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eligibility_operation WHERE evaluation_request_id=?",Integer.class,body.path("evaluationRequestId").asText())).isZero();
    }
    @Test void missingKeyRejected()throws Exception{mvc.perform(authorised(post(PATH)).contentType("application/json").content(body().toString())).andExpect(status().isBadRequest());}
    @Test void noAuthenticationDenied()throws Exception{mvc.perform(post(PATH).contentType("application/json").content(body().toString())).andExpect(status().isUnauthorized());}
    @Test void ordinaryUserRoleCannotQueueWork()throws Exception{var body=body();mvc.perform(post(PATH).with(jwt().jwt(b->b.claim("roles",List.of("ADMIN"))))
        .header("Idempotency-Key",body.path("evaluationRequestId").asText()).contentType("application/json").content(body.toString())).andExpect(status().isForbidden());}
    @Test void correctClientWithoutScopeDenied()throws Exception{var body=body();mvc.perform(post(PATH).with(jwt().jwt(b->b.claim("azp","orchestration-service")))
        .header("Idempotency-Key",body.path("evaluationRequestId").asText()).contentType("application/json").content(body.toString())).andExpect(status().isForbidden());}
    @Test void fractionalRevisionRejected()throws Exception{var body=body();body.put("applicationRevision",1.5);mvc.perform(postBody(body)).andExpect(status().isBadRequest());}
    @Test void extraBusinessFieldsRejected()throws Exception{var body=body();body.put("financialSnapshotId",UUID.randomUUID().toString());mvc.perform(postBody(body)).andExpect(status().isBadRequest());}
    @Test void invalidReferenceRejected()throws Exception{var body=body();body.put("authorisedRetrievalReference","");mvc.perform(postBody(body)).andExpect(status().isBadRequest());}
    @Test void unknownOperationIs404()throws Exception{mvc.perform(authorised(get("/api/v1/operations/"+UUID.randomUUID()))).andExpect(status().isNotFound());}
    @Test void malformedOperationIdIs400()throws Exception{mvc.perform(authorised(get("/api/v1/operations/not-a-uuid"))).andExpect(status().isBadRequest());}
    @Test void sellerCannotReadOperationStatus()throws Exception{var receipt=submit(body());mvc.perform(get(receipt.path("statusUrl").asText()).with(jwt().jwt(b->b.claim("roles",List.of("SELLER"))))).andExpect(status().isForbidden());}
    @Test void colonCorrelationSurvivesTransportAndStorage()throws Exception{
        var body=body();mvc.perform(postBody(body).header("X-Correlation-ID","member3:flow-104")).andExpect(status().isAccepted()).andExpect(header().string("X-Correlation-ID","member3:flow-104"));
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM eligibility_operation WHERE evaluation_request_id=?",String.class,body.path("evaluationRequestId").asText())).isEqualTo("member3:flow-104");
    }
    @Test void concurrentIntakeReturnsOneDurableOperation()throws Exception{
        var body=body();var command=new EligibilityOperationStore.Command(UUID.fromString(body.path("evaluationRequestId").asText()),UUID.fromString(body.path("applicationId").asText()),1,
            UUID.fromString(body.path("verificationId").asText()),java.time.LocalDate.of(2026,10,15),"test-authorised-source");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<EligibilityOperationStore.Operation> task=()->{start.await();return operations.enqueue(command,"orchestration-service","test:concurrent");};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(a.get(20,TimeUnit.SECONDS).operationId()).isEqualTo(b.get(20,TimeUnit.SECONDS).operationId());
        }finally{pool.shutdownNow();pool.awaitTermination(20,TimeUnit.SECONDS);}
    }
    @Test void full255CharacterReferenceIsSavedAndRetryIsStable()throws Exception {
        var body=body();body.put("authorisedRetrievalReference","r".repeat(255));
        var first=submit(body);var second=submit(body);assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT LENGTH(retrieval_reference) FROM eligibility_operation WHERE operation_id=?",Integer.class,first.path("operationId").asText())).isEqualTo(255);
    }
    @Test void referenceLongerThan255IsRejectedBeforeSaving()throws Exception {
        var body=body();body.put("authorisedRetrievalReference","r".repeat(256));
        mvc.perform(postBody(body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eligibility_operation WHERE evaluation_request_id=?",Integer.class,body.path("evaluationRequestId").asText())).isZero();
    }
}
