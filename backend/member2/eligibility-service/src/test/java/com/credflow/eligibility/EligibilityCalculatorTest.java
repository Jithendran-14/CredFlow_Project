package com.credflow.eligibility;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class EligibilityCalculatorTest {
 final EligibilityCalculator engine=new EligibilityCalculator();
 static BigDecimal b(String x){return new BigDecimal(x);}
 final LocalDate asOf=LocalDate.of(2026,10,13), start=LocalDate.of(2024,1,1);
 EligibilityCalculator.Product bank(String returnThreshold,String multiplier) {
  return new EligibilityCalculator.Product("bank-product","bank",1,12,b("300000.00"),b(returnThreshold),b("100000.00"),b("1000000.00"),b(multiplier),1,Set.of("PURCHASE_INVENTORY","WORKING_CAPITAL"));
 }
 EligibilityCalculator.Result eval(String gross,String returns,String amount,String purpose,EligibilityCalculator.Product p) {
  return engine.evaluate(new EligibilityCalculator.Financials("EVALUATED",null,b(gross),b(returns),b("1590000.00")),start,asOf,b(amount),purpose,List.of(p));
 }
 @Test void happyPathHasExpectedSummaryAndLimit() {
  var r=eval("1800000.00","150000.00","500000.00","PURCHASE_INVENTORY",bank("15","1.0000"));
  assertThat(r.averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
  assertThat(r.averageMonthlyPayout()).isEqualByComparingTo("530000.00");
  assertThat(r.returnRatePercent()).isEqualByComparingTo("8.33");
  assertThat(r.lenderResults().getFirst().indicativeMaxAmount()).isEqualByComparingTo("550000.00");
  assertThat(r.lenderResults().getFirst().eligible()).isTrue();
 }
 @Test void roundedReturnDisplayDoesNotHideThresholdBreach() {
  var r=eval("1800000.00","270000.01","100000.00","WORKING_CAPITAL",bank("15","1"));
  assertThat(r.returnRatePercent()).isEqualByComparingTo("15.00");
  assertThat(r.lenderResults().getFirst().reasonCodes()).contains("RETURN_RATE_ABOVE_MAXIMUM");
 }
 @Test void limitDoesNotRoundAverageBeforeMultiplication() {
  var r=eval("1000000.00","0.00","100000.00","WORKING_CAPITAL",bank("15","1.2345"));
  assertThat(r.lenderResults().getFirst().indicativeMaxAmount()).isEqualByComparingTo("411500.00");
 }
 @Test void unsupportedPurposeIsExplained() {
  assertThat(eval("1800000","150000","500000.00","BUSINESS_EXPANSION",bank("15","1")).lenderResults().getFirst().reasonCodes()).containsExactly("PURPOSE_NOT_SUPPORTED");
 }
 @Test void exactLimitPassesAndOneCentAboveFails() {
  assertThat(eval("1800000","150000","550000.00","WORKING_CAPITAL",bank("15","1")).lenderResults().getFirst().eligible()).isTrue();
  assertThat(eval("1800000","150000","550000.01","WORKING_CAPITAL",bank("15","1")).lenderResults().getFirst().reasonCodes()).containsExactly("AMOUNT_ABOVE_INDICATIVE_LIMIT");
 }
 @Test void zeroSalesNeedsActionNotRejection() {
  var r=eval("0","0","500000.00","WORKING_CAPITAL",bank("15","1"));
  assertThat(r.outcome()).isEqualTo("ACTION_REQUIRED");assertThat(r.lenderResults()).isEmpty();
 }
 @Test void incompleteDataDoesNotProduceLenderResults() {
  var r=engine.evaluate(new EligibilityCalculator.Financials("ACTION_REQUIRED","FINANCIAL_DATA_INCOMPLETE",b("0"),b("0"),b("0")),start,asOf,b("500000.00"),"WORKING_CAPITAL",List.of(bank("15","1")));
  assertThat(r.reasonCodes()).containsExactly("FINANCIAL_DATA_INCOMPLETE");assertThat(r.lenderResults()).isEmpty();
 }
 @Test void completeMonthsControlBusinessAge() {
  var f=new EligibilityCalculator.Financials("EVALUATED",null,b("1800000"),b("150000"),b("1590000"));
  var r=engine.evaluate(f,asOf.minusMonths(12).plusDays(1),asOf,b("500000.00"),"WORKING_CAPITAL",List.of(bank("15","1")));
  assertThat(r.lenderResults().getFirst().reasonCodes()).containsExactly("BUSINESS_TOO_NEW");
 }
 @Test void invalidAmountIsRejected() {
  assertThatThrownBy(()->eval("1800000","150000","-1.00","WORKING_CAPITAL",bank("15","1"))).isInstanceOf(IllegalArgumentException.class);
 }
}
