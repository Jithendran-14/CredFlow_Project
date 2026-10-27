package com.credflow.eligibility;
import com.credflow.eligibility.integration.Member1Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:seller-api;MODE=Oracle;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=none","credflow.eligibility.worker-enabled=false","credflow.eligibility.publisher-enabled=false"})
@AutoConfigureMockMvc
class SellerEligibilityApiTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;
 @MockitoBean JwtDecoder decoder;@MockitoBean Member1Client client;@MockitoBean EligibilityEvaluationService results;@MockitoBean EligibilityOperationStore operations;
 final UUID app=UUID.randomUUID(),resultId=UUID.randomUUID(),evaluation=UUID.randomUUID(),verification=UUID.randomUUID(),snapshot=UUID.randomUUID();
 String url(){return "/api/v1/applications/"+app+"/eligibility";}
 org.springframework.test.web.servlet.request.RequestPostProcessor seller(){return jwt().jwt(j->j.claim("sellerId","SELLER-1001")).authorities(new SimpleGrantedAuthority("ROLE_SELLER"));}
 String workflow(boolean ready,int revision)throws Exception{var n=json.createObjectNode().put("applicationId",app.toString()).put("applicationRevision",revision).put("status","ELIGIBILITY_CHECKING");if(ready)n.put("eligibilityResultId",resultId.toString()).put("evaluationRequestId",evaluation.toString()).put("verificationId",verification.toString());return n.toString();}
 @BeforeEach void setup()throws Exception{
  lenient().when(client.financialAvailability(eq(app),anyString(),anyString())).thenReturn("{\"applicationId\":\""+app+"\",\"connected\":true}");
  when(client.ownership(eq(app),eq("SELLER-1001"),anyString())).thenReturn("{\"applicationId\":\""+app+"\",\"ownsApplication\":true}");
  when(client.workflow(eq(app),anyString(),anyString())).thenReturn(workflow(true,1));
  var calculated=new EligibilityCalculator.Result("EVALUATED",List.of(),new BigDecimal("490000.00"),new BigDecimal("450000.00"),new BigDecimal("2.00"),List.of());
  var saved=new EligibilityEvaluationService.StoredEvaluation(resultId,evaluation,app,1,snapshot,verification,"1.0",OffsetDateTime.now(ZoneOffset.UTC),new BigDecimal("500000.00"),"INR","WORKING_CAPITAL",LocalDate.of(2022,1,1),LocalDate.of(2026,10,19),calculated);
  when(results.findById(resultId)).thenReturn(Optional.of(saved));
  when(client.application(eq(app),anyString())).thenReturn("{\"applicationId\":\""+app+"\",\"applicationRevision\":1,\"sellerId\":\"SELLER-1001\",\"requestedAmount\":\"500000.00\",\"currency\":\"INR\",\"loanPurpose\":\"WORKING_CAPITAL\",\"businessProfile\":{\"businessStartDate\":\"2022-01-01\"}}");
 }
 @Test void anonymousDenied()throws Exception{mvc.perform(get(url())).andExpect(status().isUnauthorized());verifyNoInteractions(client);}
 @Test void adminCannotUseSellerEndpoint()throws Exception{mvc.perform(get(url()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isForbidden());verifyNoInteractions(client);}
 @Test void anotherSellerCannotReadStoredResult()throws Exception{when(client.ownership(eq(app),anyString(),anyString())).thenReturn("{\"applicationId\":\""+app+"\",\"ownsApplication\":false}");mvc.perform(get(url()).with(seller())).andExpect(status().isNotFound());verifyNoInteractions(results);}
 @Test void pendingWorkflowDoesNotExposeHistoricalResult()throws Exception{when(client.workflow(eq(app),anyString(),anyString())).thenReturn(workflow(false,1));mvc.perform(get(url()).with(seller())).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));verifyNoInteractions(results);}
 @Test void currentOwnedResultReturnedWithoutCaching()throws Exception{mvc.perform(get(url()).with(seller())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.averageMonthlyNetSales").value("490000.00"));}
 @Test void olderRevisionCannotBePresentedAsCurrent()throws Exception{when(client.workflow(eq(app),anyString(),anyString())).thenReturn(workflow(true,2));mvc.perform(get(url()).with(seller())).andExpect(status().isConflict());}
 @Test void upstreamOutageDoesNotBypassOwnership()throws Exception{when(client.ownership(eq(app),anyString(),anyString())).thenThrow(new Member1Client.UpstreamUnavailable());mvc.perform(get(url()).with(seller())).andExpect(status().isServiceUnavailable());verifyNoInteractions(results);}
 @Test void unlinkedFinancialHistoryIsNotEndlessProcessing()throws Exception{
  when(client.workflow(eq(app),anyString(),anyString())).thenReturn(workflow(false,1));
  when(client.financialAvailability(eq(app),anyString(),anyString())).thenReturn("{\"applicationId\":\""+app+"\",\"connected\":false,\"message\":\"No business financial history is connected.\"}");
  mvc.perform(get(url()).with(seller())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_AVAILABLE")).andExpect(jsonPath("$.reasonCode").value("FINANCIAL_HISTORY_NOT_CONNECTED"));verifyNoInteractions(results);
 }
 @Test void failedIdentityIsBlockedRatherThanAnEndlessPendingResult()throws Exception{when(client.workflow(eq(app),anyString(),anyString())).thenReturn(workflow(false,1).replace("ELIGIBILITY_CHECKING","CHECK_FAILED"));mvc.perform(get(url()).with(seller())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));verifyNoInteractions(results);}
 @Test void failedWorkerDoesNotLookPendingForever()throws Exception{UUID operation=UUID.randomUUID();var w=json.readTree(workflow(false,1));((com.fasterxml.jackson.databind.node.ObjectNode)w).put("eligibilityOperationId",operation.toString());when(client.workflow(eq(app),anyString(),anyString())).thenReturn(w.toString());when(operations.find(operation,"orchestration-service")).thenReturn(Optional.of(new EligibilityOperationStore.Operation(operation,app,1,"FAILED","2026-10-19T09:00:00Z",null,"ELIGIBILITY_PROCESSING_FAILED")));mvc.perform(get(url()).with(seller())).andExpect(status().isOk()).andExpect(jsonPath("$.assessmentStatus").value("FAILED"));}
}
