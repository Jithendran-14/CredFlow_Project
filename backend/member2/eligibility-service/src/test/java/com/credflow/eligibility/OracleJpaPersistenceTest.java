package com.credflow.eligibility;
import com.credflow.platform.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties="credflow.eligibility.publisher-enabled=false")
@EnabledIfEnvironmentVariable(named="ORACLE_FINANCIAL_TESTS",matches="true")
@Transactional
class OracleJpaPersistenceTest {
 @Autowired FinancialEvaluationPipeline pipeline;
 @Autowired EligibilityCompletionOutbox outbox;
 @Autowired DatabaseAccess database;
 @Autowired EligibilityEvaluationService evaluations;
 @MockitoBean JwtDecoder decoder;
 @Test void actualJpaPathRunsPlsqlPersistsResultsAndReplaysOneEvent(){
  var source=new OracleFinancialImportTest().source(UUID.randomUUID(),1,false);
  UUID request=UUID.randomUUID(),verification=UUID.randomUUID();
  var first=pipeline.evaluate(source,request,verification,java.time.LocalDate.of(2020,1,1),new java.math.BigDecimal("500000.00"),"WORKING_CAPITAL");
  var second=pipeline.evaluate(source,request,verification,java.time.LocalDate.of(2020,1,1),new java.math.BigDecimal("500000.00"),"WORKING_CAPITAL");
  assertThat(second.eligibilityResultId()).isEqualTo(first.eligibilityResultId());
  assertThat(evaluations.findById(first.eligibilityResultId())).isPresent();
  UUID event=outbox.record(first,EligibilityTestContext.metadata());
  assertThat(outbox.record(second,EligibilityTestContext.metadata())).isEqualTo(event);
  assertThat(database.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_id=?",Integer.class,event.toString())).isEqualTo(1);
  assertThat(first.result().averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
 }
 @Test void duplicateInboxClaimLeavesJpaTransactionUsable(){
  String id=UUID.randomUUID().toString();database.update("INSERT INTO event_inbox(event_id) VALUES(?)",id);
  assertThatThrownBy(()->database.update("INSERT INTO event_inbox(event_id) VALUES(?)",id)).isInstanceOf(DuplicateKeyException.class);
  assertThat(database.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE event_id=?",Integer.class,id)).isEqualTo(1);
  database.update("INSERT INTO event_inbox(event_id) VALUES(?)",UUID.randomUUID().toString());
 }
}
