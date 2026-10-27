package com.credflow.eligibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional calculation storage used by the trusted worker and ownership-protected result API. */
@Service
public class EligibilityEvaluationService {
    public static final String CALCULATION_VERSION="phase0-2.0";
    private static final TypeReference<List<String>> REASONS=new TypeReference<>() {};
    private final DatabaseAccess database;
    private final FinancialSnapshotRepository snapshots;
    private final ObjectMapper json;
    private final EligibilityCalculator calculator=new EligibilityCalculator();

    public record Command(UUID evaluationRequestId, UUID applicationId, int applicationRevision,
            UUID financialSnapshotId, UUID verificationId, LocalDate businessStartDate,
            LocalDate asOfDate, BigDecimal requestedAmount, String currency, String loanPurpose,EligibilityCalculator.Terms terms) {public Command(UUID evaluation,UUID application,int revision,UUID snapshot,UUID verification,LocalDate start,LocalDate asOf,BigDecimal amount,String currency,String purpose){this(evaluation,application,revision,snapshot,verification,start,asOf,amount,currency,purpose,null);}}
    public record StoredEvaluation(UUID eligibilityResultId, UUID evaluationRequestId,
            UUID applicationId, int applicationRevision, UUID financialSnapshotId, UUID verificationId,
            String calculationVersion, OffsetDateTime evaluatedAt, BigDecimal requestedAmount,
            String currency, String loanPurpose, LocalDate businessStartDate, LocalDate asOfDate,
            EligibilityCalculator.Result result) {}
    public static class EvaluationConflictException extends RuntimeException {
        public EvaluationConflictException(String message) { super(message); }
    }
    public EligibilityEvaluationService(DatabaseAccess database, FinancialSnapshotRepository snapshots, ObjectMapper json) {
        this.database=database; this.snapshots=snapshots; this.json=json;
    }

    @Transactional
    public StoredEvaluation evaluateAndSave(Command command) {
        // Also protects callers which accidentally instantiate this class without Spring's transaction proxy.
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Evaluation persistence requires a database transaction");
        Command c=validated(command);
        String hash=hash(c);
        // Existing replies remain stable even after product configuration changes.
        var existing=findByRequest(c.evaluationRequestId());
        if (existing.isPresent()) return replay(c,hash,existing.get());

        // Lock the immutable snapshot header to serialize retries using the same financial input.
        var identity=database.queryForMap("SELECT application_id,application_revision,as_of_date FROM financial_snapshot WHERE snapshot_id=? FOR UPDATE",c.financialSnapshotId().toString());
        if (!c.applicationId().toString().equals(identity.get("APPLICATION_ID"))
            || ((Number)identity.get("APPLICATION_REVISION")).intValue()!=c.applicationRevision())
            throw new EvaluationConflictException("Snapshot belongs to a different application or revision");
        LocalDate snapshotDate=database.queryForObject("SELECT as_of_date FROM financial_snapshot WHERE snapshot_id=?",(rs,n)->rs.getDate(1).toLocalDate(),c.financialSnapshotId().toString());
        if (!c.asOfDate().equals(snapshotDate))
            throw new EvaluationConflictException("Evaluation date does not match the financial snapshot");
        // A concurrent caller may have committed while this caller waited for the snapshot lock.
        existing=findByRequest(c.evaluationRequestId());
        if(existing.isPresent()) return replay(c,hash,existing.get());

        var result=calculator.evaluate(snapshots.financials(c.financialSnapshotId().toString()),
            c.businessStartDate(),c.asOfDate(),c.requestedAmount(),c.loanPurpose(),snapshots.activeProducts(),c.terms());
        String resultId=UUID.randomUUID().toString();
        try {
            database.update("INSERT INTO eligibility_evaluation(eligibility_result_id,evaluation_request_id,application_id,application_revision,financial_snapshot_id,verification_id,request_hash,calculation_version,requested_amount,currency,loan_purpose,business_start_date,as_of_date,outcome,reason_codes,average_monthly_net,average_monthly_payout,return_rate_percent,credit_score_band,monthly_surplus) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                resultId,c.evaluationRequestId().toString(),c.applicationId().toString(),c.applicationRevision(),
                c.financialSnapshotId().toString(),c.verificationId().toString(),hash,CALCULATION_VERSION,c.requestedAmount(),
                c.currency(),c.loanPurpose(),java.sql.Date.valueOf(c.businessStartDate()),java.sql.Date.valueOf(c.asOfDate()),
                result.outcome(),encode(result.reasonCodes()),result.averageMonthlyNetSales(),result.averageMonthlyPayout(),result.returnRatePercent(),result.creditScoreBand(),result.monthlySurplus());
        } catch (DuplicateKeyException conflict) {
            // Another snapshot used the same globally unique request ID. Roll back, never overwrite it.
            throw new EvaluationConflictException("Evaluation request was already recorded; retry the original request");
        }
        for (var lender:result.lenderResults()) {
            database.update("INSERT INTO eligibility_lender_result(eligibility_result_id,product_id,lender_id,rule_version,indicative_max_amount,eligible,reason_codes,routing_priority,estimated_monthly_payment) VALUES(?,?,?,?,?,?,?,?,?)",
                resultId,lender.productId(),lender.lenderId(),lender.ruleVersion(),lender.indicativeMaxAmount(),
                lender.eligible()?1:0,encode(lender.reasonCodes()),lender.routingPriority(),lender.estimatedMonthlyPayment());
        }
        return findById(UUID.fromString(resultId)).orElseThrow();
    }

    @Transactional(readOnly=true)
    public Optional<StoredEvaluation> findById(UUID id) {
        Objects.requireNonNull(id);
        var records=database.query("SELECT * FROM eligibility_evaluation WHERE eligibility_result_id=?",(rs,n)-> {
            var lenders=database.query("SELECT * FROM eligibility_lender_result WHERE eligibility_result_id=? ORDER BY routing_priority,product_id",
                (l,i)->new EligibilityCalculator.LenderResult(l.getString("product_id"),l.getString("lender_id"),l.getInt("rule_version"),
                    l.getBigDecimal("indicative_max_amount"),l.getInt("eligible")==1,decode(l.getString("reason_codes")),l.getInt("routing_priority"),l.getBigDecimal("estimated_monthly_payment")),id.toString());
            var result=new EligibilityCalculator.Result(rs.getString("outcome"),decode(rs.getString("reason_codes")),
                rs.getBigDecimal("average_monthly_net"),rs.getBigDecimal("average_monthly_payout"),rs.getBigDecimal("return_rate_percent"),List.copyOf(lenders),rs.getString("credit_score_band"),rs.getBigDecimal("monthly_surplus"));
            return new StoredEvaluation(id,UUID.fromString(rs.getString("evaluation_request_id")),UUID.fromString(rs.getString("application_id")),
                rs.getInt("application_revision"),UUID.fromString(rs.getString("financial_snapshot_id")),UUID.fromString(rs.getString("verification_id")),
                rs.getString("calculation_version"),rs.getObject("evaluated_at",OffsetDateTime.class),rs.getBigDecimal("requested_amount"),
                rs.getString("currency"),rs.getString("loan_purpose"),rs.getDate("business_start_date").toLocalDate(),rs.getDate("as_of_date").toLocalDate(),result);
        },id.toString());
        return records.stream().findFirst();
    }
    private Optional<StoredEvaluation> findByRequest(UUID id) {
        var ids=database.queryForList("SELECT eligibility_result_id FROM eligibility_evaluation WHERE evaluation_request_id=?",String.class,id.toString());
        return ids.isEmpty()?Optional.empty():findById(UUID.fromString(ids.getFirst()));
    }
    private StoredEvaluation replay(Command c,String hash,StoredEvaluation existing) {
        String storedHash=database.queryForObject("SELECT request_hash FROM eligibility_evaluation WHERE evaluation_request_id=?",String.class,c.evaluationRequestId().toString());
        if(!hash.equals(storedHash)) throw new EvaluationConflictException("The evaluation request ID was reused with different inputs");
        return existing;
    }
    private Command validated(Command c) {
        Objects.requireNonNull(c,"Command is required");
        Objects.requireNonNull(c.evaluationRequestId());Objects.requireNonNull(c.applicationId());
        Objects.requireNonNull(c.financialSnapshotId());Objects.requireNonNull(c.verificationId());
        Objects.requireNonNull(c.businessStartDate());Objects.requireNonNull(c.asOfDate());Objects.requireNonNull(c.requestedAmount());
        if(c.applicationRevision()<1 || !"INR".equals(c.currency()) || c.requestedAmount().signum()<=0
            || c.requestedAmount().scale()>2 || c.requestedAmount().precision()-c.requestedAmount().scale()>17
            || c.businessStartDate().isAfter(c.asOfDate())
            || !Set.of("PURCHASE_INVENTORY","WORKING_CAPITAL","BUSINESS_EXPANSION").contains(Objects.requireNonNull(c.loanPurpose())))
            throw new IllegalArgumentException("Invalid evaluation inputs");
        return new Command(c.evaluationRequestId(),c.applicationId(),c.applicationRevision(),c.financialSnapshotId(),c.verificationId(),
            c.businessStartDate(),c.asOfDate(),c.requestedAmount().setScale(2,RoundingMode.UNNECESSARY),c.currency(),c.loanPurpose(),c.terms());
    }
    private String hash(Command c) {
        try {
            // Explicit ordered fields: independent of JSON property-order or date-module configuration.
            String canonical=String.join("|",c.applicationId().toString(),Integer.toString(c.applicationRevision()),
                c.financialSnapshotId().toString(),c.verificationId().toString(),c.businessStartDate().toString(),
                c.asOfDate().toString(),c.requestedAmount().toPlainString(),c.currency(),c.loanPurpose(),Objects.toString(c.terms(),""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String encode(List<String> reasons) {
        try { return json.writeValueAsString(reasons); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Cannot encode reasons",ex); }
    }
    private List<String> decode(String value) {
        try { return List.copyOf(json.readValue(value,REASONS)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid stored reasons",ex); }
    }
}
