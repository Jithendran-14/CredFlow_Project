package com.credflow.eligibility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure calculation: no routing, HTTP, database access or workflow changes. */
public final class EligibilityCalculator {
    public record Financials(String outcome, String reasonCode, BigDecimal gross,
            BigDecimal returns, BigDecimal payout,String creditScoreBand) { public Financials(String outcome,String reason,BigDecimal gross,BigDecimal returns,BigDecimal payout){this(outcome,reason,gross,returns,payout,"GOOD");} }
    public record Terms(int tenureMonths,BigDecimal declaredIncome,BigDecimal expenses,BigDecimal existingDebt) {}
    public record Product(String productId, String lenderId, int ruleVersion, int minAgeMonths,
            BigDecimal minAverageNet, BigDecimal maxReturnPercent, BigDecimal minAmount,
            BigDecimal maxAmount, BigDecimal multiplier, int routingPriority, Set<String> purposes,String minCreditScoreBand,BigDecimal maxRepaymentPercent,BigDecimal assessmentAprPercent) {
        public Product(String product,String lender,int version,int age,BigDecimal net,BigDecimal returns,BigDecimal min,BigDecimal max,BigDecimal multiplier,int priority,Set<String> purposes){this(product,lender,version,age,net,returns,min,max,multiplier,priority,purposes,"FAIR",new BigDecimal("40"),new BigDecimal("18"));}
        public Product { purposes = Set.copyOf(purposes); }
    }
    public record LenderResult(String productId, String lenderId, int ruleVersion,
            BigDecimal indicativeMaxAmount, boolean eligible, List<String> reasonCodes, int routingPriority,BigDecimal estimatedMonthlyPayment) {public LenderResult(String product,String lender,int version,BigDecimal max,boolean eligible,List<String> reasons,int priority){this(product,lender,version,max,eligible,reasons,priority,null);}}
    public record Result(String outcome, List<String> reasonCodes, BigDecimal averageMonthlyNetSales,
            BigDecimal averageMonthlyPayout, BigDecimal returnRatePercent, List<LenderResult> lenderResults,String creditScoreBand,BigDecimal monthlySurplus) { public Result(String outcome,List<String> reasons,BigDecimal net,BigDecimal payout,BigDecimal returns,List<LenderResult> lenders){this(outcome,reasons,net,payout,returns,lenders,null,null);} }

    public Result evaluate(Financials f, LocalDate businessStart, LocalDate asOf,
            BigDecimal amount, String purpose, List<Product> products) {
        return evaluate(f,businessStart,asOf,amount,purpose,products,null);
    }
    public Result evaluate(Financials f,LocalDate businessStart,LocalDate asOf,BigDecimal amount,String purpose,List<Product> products,Terms terms){
        Objects.requireNonNull(f); Objects.requireNonNull(businessStart); Objects.requireNonNull(asOf);
        Objects.requireNonNull(amount); Objects.requireNonNull(purpose); Objects.requireNonNull(products);
        if (amount.signum() <= 0 || amount.scale() > 2 || businessStart.isAfter(asOf))
            throw new IllegalArgumentException("Invalid amount or business start date");
        if (!Set.of("PURCHASE_INVENTORY","WORKING_CAPITAL","BUSINESS_EXPANSION").contains(purpose))
            throw new IllegalArgumentException("Unknown loan purpose");
        if ("ACTION_REQUIRED".equals(f.outcome())) {
            if (f.reasonCode()==null) throw new IllegalArgumentException("Data outcome needs a reason");
            return action(f.reasonCode());
        }
        if (!"EVALUATED".equals(f.outcome())) throw new IllegalArgumentException("Unknown financial outcome");
        if (f.gross().signum()<0 || f.returns().signum()<0 || f.payout().signum()<0 || f.returns().compareTo(f.gross())>0)
            return action("FINANCIAL_DATA_INCONSISTENT");
        if (f.gross().signum()==0) return action("FINANCIAL_DATA_INSUFFICIENT");
        if(!List.of("POOR","FAIR","GOOD","EXCELLENT").contains(f.creditScoreBand()))return action("CREDIT_INFORMATION_REQUIRED");
        if(terms!=null&&(terms.tenureMonths()<1||terms.tenureMonths()>120||terms.declaredIncome()==null||terms.expenses()==null||terms.existingDebt()==null||terms.declaredIncome().signum()<=0||terms.expenses().signum()<0||terms.existingDebt().signum()<0))throw new IllegalArgumentException("Invalid affordability inputs");
        BigDecimal three=BigDecimal.valueOf(3), hundred=BigDecimal.valueOf(100);
        BigDecimal surplus=terms==null?null:terms.declaredIncome().min(f.payout().divide(three,2,RoundingMode.HALF_UP)).subtract(terms.expenses()).subtract(terms.existingDebt()).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
        BigDecimal net=f.gross().subtract(f.returns());
        long age=ChronoUnit.MONTHS.between(businessStart,asOf);
        List<LenderResult> results=new ArrayList<>();
        for (Product p:products) {
            List<String> reasons=new ArrayList<>();
            BigDecimal limit=net.multiply(p.multiplier()).divide(three,2,RoundingMode.HALF_UP);
            if(band(f.creditScoreBand())<band(p.minCreditScoreBand()))reasons.add("CREDIT_SCORE_BAND_BELOW_MINIMUM");
            BigDecimal payment=terms==null?null:payment(amount,terms.tenureMonths(),p.assessmentAprPercent());
            if(terms!=null&&(surplus.signum()<=0||payment.compareTo(surplus.multiply(p.maxRepaymentPercent()).divide(hundred,2,RoundingMode.DOWN))>0))reasons.add("AFFORDABILITY_NOT_MET");
            if (age<p.minAgeMonths()) reasons.add("BUSINESS_TOO_NEW");
            if (net.compareTo(p.minAverageNet().multiply(three))<0) reasons.add("NET_SALES_BELOW_MINIMUM");
            if (f.returns().multiply(hundred).compareTo(f.gross().multiply(p.maxReturnPercent()))>0)
                reasons.add("RETURN_RATE_ABOVE_MAXIMUM");
            if (amount.compareTo(p.minAmount())<0) reasons.add("AMOUNT_BELOW_MINIMUM");
            if (amount.compareTo(p.maxAmount())>0) reasons.add("AMOUNT_ABOVE_MAXIMUM");
            if (amount.compareTo(limit)>0) reasons.add("AMOUNT_ABOVE_INDICATIVE_LIMIT");
            if (!p.purposes().contains(purpose)) reasons.add("PURPOSE_NOT_SUPPORTED");
            results.add(new LenderResult(p.productId(),p.lenderId(),p.ruleVersion(),limit,reasons.isEmpty(),List.copyOf(reasons),p.routingPriority(),payment));
        }
        return new Result("EVALUATED",List.of(),net.divide(three,2,RoundingMode.HALF_UP),
            f.payout().divide(three,2,RoundingMode.HALF_UP),
            f.returns().multiply(hundred).divide(f.gross(),2,RoundingMode.HALF_UP),List.copyOf(results),f.creditScoreBand(),surplus);
    }
    public static int band(String value){return switch(Objects.toString(value,"")){case "POOR"->0;case "FAIR"->1;case "GOOD"->2;case "EXCELLENT"->3;default->-1;};}
    public static BigDecimal payment(BigDecimal amount,int months,BigDecimal apr){
        if(apr.signum()==0)return amount.divide(BigDecimal.valueOf(months),2,RoundingMode.HALF_UP);
        var mc=java.math.MathContext.DECIMAL128;var rate=apr.divide(new BigDecimal("1200"),mc);var factor=BigDecimal.ONE.add(rate).pow(months,mc);
        return amount.multiply(rate,mc).multiply(factor,mc).divide(factor.subtract(BigDecimal.ONE),2,RoundingMode.HALF_UP);
    }
    private Result action(String reason) {
        return new Result("ACTION_REQUIRED",List.of(reason),null,null,null,List.of());
    }
}
