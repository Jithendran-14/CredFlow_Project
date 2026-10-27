package com.credflow.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ORACLE_FINANCIAL_TESTS",matches="true")
class OracleFinancialImportTest {
 static FinancialImportService imports;static FinancialEvaluationPipeline pipeline;
 static final LocalDate AS_OF=LocalDate.of(2026,10,13);
 @BeforeAll static void setup() {
  OracleFinancialSnapshotTest.setup();var jdbc=OracleFinancialSnapshotTest.jdbc;var repo=OracleFinancialSnapshotTest.repo;var json=new ObjectMapper();
  imports=new FinancialImportService(new JdbcTestAccess(jdbc),repo,json);
  pipeline=new FinancialEvaluationPipeline(imports,new EligibilityEvaluationService(new JdbcTestAccess(jdbc),repo,json));
 }
 FinancialImportService.Command source(UUID app,int dataVersion,boolean missingCount) {
  List<FinancialImportService.MonthlyMetrics> months=new ArrayList<>();
  for(int i=3;i>=1;i--)months.add(new FinancialImportService.MonthlyMetrics(YearMonth.from(AS_OF).minusMonths(i),FinancialImportService.Coverage.COMPLETE,
   new BigDecimal("600000.00"),new BigDecimal("50000.00"),new BigDecimal("530000.00"),600L,missingCount?null:50L));
  return new FinancialImportService.Command(app,1,AS_OF,"VERIFICATION_MOCK","FIN-DATA-001",dataVersion,Instant.parse("2026-10-15T09:00:00Z"),"INR",months);
 }
 int batches(UUID app){return OracleFinancialSnapshotTest.jdbc.queryForObject("SELECT COUNT(*) FROM financial_import_batch WHERE application_id=?",Integer.class,app.toString());}
 @Test void importsSummarizesEvaluatesAndSavesOneCoherentResult() {
  OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   UUID app=UUID.randomUUID(),verification=UUID.randomUUID();var source=source(app,1,false);
   var saved=pipeline.evaluate(source,UUID.randomUUID(),verification,LocalDate.of(2024,1,1),new BigDecimal("500000.00"),"WORKING_CAPITAL");
   assertThat(saved.applicationId()).isEqualTo(app);assertThat(saved.verificationId()).isEqualTo(verification);
   assertThat(saved.result().averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
   assertThat(saved.result().lenderResults()).hasSize(2).allMatch(EligibilityCalculator.LenderResult::eligible);
   assertThat(batches(app)).isEqualTo(1);
   assertThat(OracleFinancialSnapshotTest.jdbc.queryForObject("SELECT source_data_version FROM financial_import_batch WHERE application_id=?",Integer.class,app.toString())).isEqualTo(1);
   System.out.println("IMPORT PIPELINE CHECK: monthly records imported, Oracle snapshot built, eligibility calculated and result saved in one transaction");
   status.setRollbackOnly();
  });
 }
 @Test void repeatWithReorderedMonthsAndLaterRetrievalReusesOriginalSnapshot() {
  OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   var c=source(UUID.randomUUID(),1,false);var first=imports.importAndBuild(c);
   var reordered=new ArrayList<>(c.months());Collections.reverse(reordered);
   var repeated=new FinancialImportService.Command(c.applicationId(),1,AS_OF,c.sourceProvider(),c.dataReference(),1,c.retrievedAt().plusSeconds(60),"INR",reordered);
   assertThat(imports.importAndBuild(repeated)).isEqualTo(first);assertThat(batches(c.applicationId())).isEqualTo(1);status.setRollbackOnly();
  });
 }
 @Test void sameSourceVersionCannotSilentlyReplaceFinancialValues() {
  OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   var c=source(UUID.randomUUID(),1,false);var first=imports.importAndBuild(c);var months=new ArrayList<>(c.months());var m=months.getFirst();
   months.set(0,new FinancialImportService.MonthlyMetrics(m.month(),m.coverage(),new BigDecimal("650000.00"),m.returnsAmount(),m.payoutAmount(),m.orderCount(),m.returnedOrderCount()));
   var changed=new FinancialImportService.Command(c.applicationId(),1,AS_OF,c.sourceProvider(),c.dataReference(),1,c.retrievedAt(),"INR",months);
   assertThatThrownBy(()->imports.importAndBuild(changed)).isInstanceOf(FinancialImportService.SourceDataConflictException.class);
   assertThat(batches(c.applicationId())).isEqualTo(1);
   assertThat(OracleFinancialSnapshotTest.repo.financials(first.financialSnapshotId().toString()).gross()).isEqualByComparingTo("1800000.00");status.setRollbackOnly();
  });
 }
 @Test void aNewDataVersionGetsANewSnapshotWithoutChangingTheOldOne() {
  OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   UUID app=UUID.randomUUID();var first=imports.importAndBuild(source(app,1,false));var second=imports.importAndBuild(source(app,2,false));
   assertThat(second.financialSnapshotId()).isNotEqualTo(first.financialSnapshotId());assertThat(batches(app)).isEqualTo(2);
   assertThat(OracleFinancialSnapshotTest.repo.financials(first.financialSnapshotId().toString()).gross()).isEqualByComparingTo("1800000.00");status.setRollbackOnly();
  });
 }
 @Test void unknownReturnedCountProducesActionRequiredInsteadOfZero() {
  OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   UUID app=UUID.randomUUID();var saved=pipeline.evaluate(source(app,1,true),UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2024,1,1),new BigDecimal("500000.00"),"WORKING_CAPITAL");
   assertThat(saved.result().outcome()).isEqualTo("ACTION_REQUIRED");assertThat(saved.result().reasonCodes()).containsExactly("FINANCIAL_DATA_INCOMPLETE");
   assertThat(saved.result().lenderResults()).isEmpty();
   assertThat(OracleFinancialSnapshotTest.jdbc.queryForObject("SELECT COUNT(*) FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?) AND returned_order_count IS NULL",Integer.class,app.toString())).isEqualTo(3);
   status.setRollbackOnly();
  });
 }
 @Test void downstreamFailureRollsBackImportedDataAndSnapshot() {
  UUID app=UUID.randomUUID();
  assertThatThrownBy(()->OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{
   pipeline.evaluate(source(app,1,false),UUID.randomUUID(),UUID.randomUUID(),AS_OF.plusDays(1),new BigDecimal("500000.00"),"WORKING_CAPITAL");
  })).isInstanceOf(IllegalArgumentException.class);
  assertThat(batches(app)).isZero();
  assertThat(OracleFinancialSnapshotTest.jdbc.queryForObject("SELECT COUNT(*) FROM financial_snapshot WHERE application_id=?",Integer.class,app.toString())).isZero();
 }
 @Test void concurrentIdenticalImportsShareOneBatchAndSnapshot() throws Exception {
  var c=source(UUID.randomUUID(),1,false);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {
   Callable<FinancialImportService.ImportedSnapshot> task=()->{start.await();return OracleFinancialSnapshotTest.tx.execute(status->imports.importAndBuild(c));};
   var first=pool.submit(task);var second=pool.submit(task);start.countDown();
   assertThat(first.get(25,TimeUnit.SECONDS)).isEqualTo(second.get(25,TimeUnit.SECONDS));assertThat(batches(c.applicationId())).isEqualTo(1);
  } finally {
   pool.shutdownNow();pool.awaitTermination(30,TimeUnit.SECONDS);
   OracleFinancialSnapshotTest.tx.executeWithoutResult(status->{var jdbc=OracleFinancialSnapshotTest.jdbc;String app=c.applicationId().toString();
    jdbc.update("DELETE FROM financial_snapshot_month WHERE snapshot_id IN (SELECT snapshot_id FROM financial_snapshot WHERE application_id=?)",app);
    jdbc.update("DELETE FROM financial_snapshot WHERE application_id=?",app);
    jdbc.update("DELETE FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?)",app);
    jdbc.update("DELETE FROM financial_import_batch WHERE application_id=?",app);
   });
  }
 }
}
