package com.credflow.eligibility;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal chain. Future controller must validate identity, consent and
 * workflow authorisation first.
 */
@Service
public class FinancialEvaluationPipeline {
	private final FinancialImportService imports;
	private final EligibilityEvaluationService evaluations;

	public FinancialEvaluationPipeline(FinancialImportService imports, EligibilityEvaluationService evaluations) {
		this.imports = imports;
		this.evaluations = evaluations;
	}

	@Transactional
	public EligibilityEvaluationService.StoredEvaluation evaluate(FinancialImportService.Command source,
			UUID evaluationRequestId, UUID verificationId, LocalDate businessStartDate, BigDecimal requestedAmount,
			String loanPurpose) {
		return evaluate(source, evaluationRequestId, verificationId, businessStartDate, requestedAmount, loanPurpose,
				null);
	}

	@Transactional
	public EligibilityEvaluationService.StoredEvaluation evaluate(FinancialImportService.Command source,
			UUID evaluationRequestId, UUID verificationId, LocalDate businessStartDate, BigDecimal requestedAmount,
			String loanPurpose, EligibilityCalculator.Terms terms) {
		Objects.requireNonNull(source);
		var imported = imports.importAndBuild(source);
		return evaluations.evaluateAndSave(new EligibilityEvaluationService.Command(evaluationRequestId,
				source.applicationId(), source.applicationRevision(), imported.financialSnapshotId(), verificationId,
				businessStartDate, source.asOfDate(), requestedAmount, source.currency(), loanPurpose, terms));
	}
}
