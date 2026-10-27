package com.credflow.eligibility.api;

import com.credflow.eligibility.EligibilityEvaluationService.StoredEvaluation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Agreed v1 response used by the seller API and completion event. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EligibilityResultResponse(
        UUID eligibilityResultId, UUID evaluationRequestId, UUID applicationId, int applicationRevision,
        UUID verificationId, UUID financialSnapshotId, String calculationVersion, String evaluatedAt,
        String asOfDate, String requestedAmount, String currency, String outcome, List<String> reasonCodes,
        String averageMonthlyNetSales, String averageMonthlyPayout, String returnRatePercent,
        List<LenderResultResponse> lenderResults,String creditScoreBand,String monthlySurplus) {
    public EligibilityResultResponse {
        reasonCodes=List.copyOf(reasonCodes);lenderResults=List.copyOf(lenderResults);
    }
    public record LenderResultResponse(UUID lenderId, UUID productId, String ruleVersion,
            String indicativeMaxAmount, boolean eligible, List<String> reasonCodes, int routingPriority,String estimatedMonthlyPayment) {
        public LenderResultResponse { reasonCodes=List.copyOf(reasonCodes); }
    }
    public static EligibilityResultResponse from(StoredEvaluation saved) {
        Objects.requireNonNull(saved,"Saved evaluation is required");
        var result=Objects.requireNonNull(saved.result());
        boolean action="ACTION_REQUIRED".equals(result.outcome());
        if(!action && !"EVALUATED".equals(result.outcome()))
            throw new IllegalArgumentException("Unknown eligibility outcome");
        if(action && (result.reasonCodes().isEmpty() || !result.lenderResults().isEmpty()
                || result.averageMonthlyNetSales()!=null || result.averageMonthlyPayout()!=null || result.returnRatePercent()!=null))
            throw new IllegalArgumentException("Action-required result must explain missing input without computed values");
        if(!action && !result.reasonCodes().isEmpty())
            throw new IllegalArgumentException("Evaluated outcome keeps reasons on the individual products");
        var lenders=result.lenderResults().stream().map(l->{
            if(l.eligible()!=l.reasonCodes().isEmpty())
                throw new IllegalArgumentException("Lender eligibility and reasons disagree");
            return new LenderResultResponse(UUID.fromString(l.lenderId()),UUID.fromString(l.productId()),
                Integer.toString(l.ruleVersion()),decimal(l.indicativeMaxAmount()),l.eligible(),l.reasonCodes(),l.routingPriority(),l.estimatedMonthlyPayment()==null?null:decimal(l.estimatedMonthlyPayment()));
        }).toList();
        return new EligibilityResultResponse(saved.eligibilityResultId(),saved.evaluationRequestId(),saved.applicationId(),
            saved.applicationRevision(),saved.verificationId(),saved.financialSnapshotId(),saved.calculationVersion(),
            saved.evaluatedAt().toInstant().toString(),saved.asOfDate().toString(),decimal(saved.requestedAmount()),saved.currency(),
            result.outcome(),result.reasonCodes(),action?null:decimal(result.averageMonthlyNetSales()),
            action?null:decimal(result.averageMonthlyPayout()),action?null:decimal(result.returnRatePercent()),lenders,result.creditScoreBand(),result.monthlySurplus()==null?null:result.monthlySurplus().setScale(2,RoundingMode.UNNECESSARY).toPlainString());
    }
    private static String decimal(BigDecimal amount) {
        Objects.requireNonNull(amount,"Computed value is missing");
        if(amount.signum()<0)throw new IllegalArgumentException("Negative response amount");
        return amount.setScale(2,RoundingMode.UNNECESSARY).toPlainString();
    }
}
