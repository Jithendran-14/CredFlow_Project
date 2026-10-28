package com.credflow.eligibility;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Call only after trusted adapters retrieve current context and authenticate
 * the service caller.
 */
@Service
public class EligibilityTrustedPipeline {
	private final EligibilityInputGuard guard;
	private final FinancialEvaluationPipeline pipeline;
	private final EligibilityCompletionOutbox outbox;

	public EligibilityTrustedPipeline(EligibilityInputGuard guard, FinancialEvaluationPipeline pipeline,
			EligibilityCompletionOutbox outbox) {
		this.guard = guard;
		this.pipeline = pipeline;
		this.outbox = outbox;
	}

	public record Completed(EligibilityEvaluationService.StoredEvaluation result, java.util.UUID eventId) {
	}

	@Transactional
	public Completed evaluate(EligibilityInputGuard.Request request, EligibilityInputGuard.Context context,
			EligibilityCompletionOutbox.Metadata metadata) {
		guard.validate(request, context);
		var a = context.application();
		var result = pipeline.evaluate(context.financialData(), request.evaluationRequestId(), request.verificationId(),
				a.businessStartDate(), a.requestedAmount(), a.loanPurpose(), a.terms());
		return new Completed(result, outbox.record(result, metadata));
	}
}
