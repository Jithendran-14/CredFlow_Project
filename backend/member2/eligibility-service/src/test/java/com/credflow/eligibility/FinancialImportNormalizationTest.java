package com.credflow.eligibility;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FinancialImportNormalizationTest {
 final LocalDate date=LocalDate.of(2026,10,13);
 FinancialImportService.MonthlyMetrics month(YearMonth month) {
  return new FinancialImportService.MonthlyMetrics(month,FinancialImportService.Coverage.COMPLETE,new BigDecimal("600000"),new BigDecimal("50000"),new BigDecimal("530000"),600L,50L);
 }
 FinancialImportService.Command command(LocalDate asOf,List<FinancialImportService.MonthlyMetrics> months) {
  return new FinancialImportService.Command(UUID.randomUUID(),1,asOf,"MOCK","DATA-001",1,Instant.parse("2026-10-15T09:00:00Z"),"INR",months);
 }
 @Test void missingMonthGetsAnExplicitIncompleteRecordWithNullValues() {
  var result=FinancialImportService.normalize(command(date,List.of(month(YearMonth.of(2026,8)),month(YearMonth.of(2026,9)))));
  assertThat(result.months()).hasSize(3);var missing=result.months().getFirst();
  assertThat(missing.month()).isEqualTo(YearMonth.of(2026,7));assertThat(missing.coverage()).isEqualTo(FinancialImportService.Coverage.INCOMPLETE);
  assertThat(missing.grossSales()).isNull();assertThat(missing.returnedOrderCount()).isNull();
 }
 @Test void missingReturnedCountDoesNotBecomeZeroOrComplete() {
  var m=month(YearMonth.of(2026,7));
  var partial=new FinancialImportService.MonthlyMetrics(m.month(),m.coverage(),m.grossSales(),m.returnsAmount(),m.payoutAmount(),m.orderCount(),null);
  var result=FinancialImportService.normalize(command(date,List.of(partial))).months().getFirst();
  assertThat(result.coverage()).isEqualTo(FinancialImportService.Coverage.INCOMPLETE);assertThat(result.returnedOrderCount()).isNull();
 }
 @Test void zeroValuesRemainCompleteAndAmountsAreExact() {
  var zero=new FinancialImportService.MonthlyMetrics(YearMonth.of(2026,7),FinancialImportService.Coverage.COMPLETE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0L,0L);
  var result=FinancialImportService.normalize(command(date,List.of(zero))).months().getFirst();
  assertThat(result.coverage()).isEqualTo(FinancialImportService.Coverage.COMPLETE);assertThat(result.grossSales().toPlainString()).isEqualTo("0.00");
 }
 @Test void duplicateMonthIsRefused() {
  var m=month(YearMonth.of(2026,7));
  assertThatThrownBy(()->FinancialImportService.normalize(command(date,List.of(m,m)))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
 }
 @Test void currentOrOlderMonthIsRefused() {
  for(var m:List.of(YearMonth.of(2026,10),YearMonth.of(2026,6)))
   assertThatThrownBy(()->FinancialImportService.normalize(command(date,List.of(month(m))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside");
 }
 @Test void JanuaryWindowCrossesTheYearBoundary() {
  var result=FinancialImportService.normalize(command(LocalDate.of(2027,1,15),List.of()));
  assertThat(result.months()).extracting(FinancialImportService.MonthlyMetrics::month)
   .containsExactly(YearMonth.of(2026,10),YearMonth.of(2026,11),YearMonth.of(2026,12));
 }
 @Test void meaningfulExtraDecimalsAreNotSilentlyRounded() {
  var bad=new FinancialImportService.MonthlyMetrics(YearMonth.of(2026,7),FinancialImportService.Coverage.COMPLETE,new BigDecimal("100.001"),BigDecimal.ZERO,BigDecimal.ZERO,1L,0L);
  assertThatThrownBy(()->FinancialImportService.normalize(command(date,List.of(bad)))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal");
 }
 @Test void negativeAmountsAndOversizedCountsAreRefused() {
  var badAmount=new FinancialImportService.MonthlyMetrics(YearMonth.of(2026,7),FinancialImportService.Coverage.COMPLETE,new BigDecimal("-1"),BigDecimal.ZERO,BigDecimal.ZERO,1L,0L);
  var badCount=new FinancialImportService.MonthlyMetrics(YearMonth.of(2026,7),FinancialImportService.Coverage.COMPLETE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,10_000_000_000L,0L);
  assertThatThrownBy(()->FinancialImportService.normalize(command(date,List.of(badAmount)))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->FinancialImportService.normalize(command(date,List.of(badCount)))).isInstanceOf(IllegalArgumentException.class);
 }
}
