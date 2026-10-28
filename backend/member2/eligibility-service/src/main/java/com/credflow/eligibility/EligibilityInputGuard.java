package com.credflow.eligibility;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Internal trusted context, not a DTO to accept from a seller. External
 * adapters are pending.
 */
@Component
public class EligibilityInputGuard {
	public record Request(UUID evaluationRequestId, UUID applicationId, int applicationRevision, UUID verificationId,
			LocalDate asOfDate) {
	}

	public record Application(UUID applicationId, int revision, LocalDate businessStartDate, BigDecimal requestedAmount,
			String currency, String loanPurpose, EligibilityCalculator.Terms terms) {
		public Application(UUID id, int revision, LocalDate start, BigDecimal amount, String currency, String purpose) {
			this(id, revision, start, amount, currency, purpose, null);
		}
	}

	public record Verification(UUID verificationId, UUID applicationId, int revision, String status) {
	}

	public record Consent(UUID applicationId, int revision, String status, String scope, Instant expiresAt,
			Instant authorityCheckedAt) {
		public Consent(UUID applicationId, int revision, String status, String scope, Instant expiresAt) {
			this(applicationId, revision, status, scope, expiresAt, null);
		}
	}

	public record Context(Application application, Verification verification, Consent consent,
			FinancialImportService.Command financialData) {
	}

	public static class ContextRejectedException extends IllegalArgumentException {
		private final String code;

		public ContextRejectedException(String code) {
			super(code);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private final Clock clock;

	public EligibilityInputGuard() {
		this(Clock.systemUTC());
	}

	public EligibilityInputGuard(Clock clock) {
		this.clock = Objects.requireNonNull(clock);
	}

	public void validate(Request request, Context context) {
		if (request == null || request.evaluationRequestId() == null || request.applicationId() == null
				|| request.verificationId() == null || request.applicationRevision() < 1 || request.asOfDate() == null)
			reject("INVALID_EVALUATION_REQUEST");
		if (context == null || context.application() == null || context.verification() == null
				|| context.consent() == null || context.financialData() == null)
			reject("TRUSTED_CONTEXT_MISSING");
		var a = context.application();
		var v = context.verification();
		var c = context.consent();
		var f = context.financialData();
		if (!request.applicationId().equals(a.applicationId()) || request.applicationRevision() != a.revision()
				|| !request.applicationId().equals(v.applicationId()) || request.applicationRevision() != v.revision()
				|| !request.applicationId().equals(c.applicationId()) || request.applicationRevision() != c.revision()
				|| !request.applicationId().equals(f.applicationId())
				|| request.applicationRevision() != f.applicationRevision())
			reject("APPLICATION_CONTEXT_MISMATCH");
		if (!request.verificationId().equals(v.verificationId()) || !"VERIFIED".equals(v.status()))
			reject("VERIFICATION_NOT_CURRENT_OR_VERIFIED");
		if (!"ACTIVE".equals(c.status()) || !"FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY".equals(c.scope())
				|| (c.expiresAt() != null ? !clock.instant().isBefore(c.expiresAt())
						: c.authorityCheckedAt() == null || c.authorityCheckedAt().isAfter(clock.instant())
								|| c.authorityCheckedAt().isBefore(clock.instant().minusSeconds(30))))
			reject("FINANCIAL_CONSENT_NOT_ACTIVE");
		if (!request.asOfDate().equals(f.asOfDate()) || !Objects.equals(a.currency(), f.currency())
				|| f.retrievedAt() == null || f.retrievedAt().isAfter(clock.instant()))
			reject("FINANCIAL_CONTEXT_MISMATCH");
		// The importer and evaluation service perform detailed field/amount/window
		// validation.
		if (a.businessStartDate() == null || a.requestedAmount() == null || a.loanPurpose() == null)
			reject("APPLICATION_DETAILS_MISSING");
	}

	private static void reject(String code) {
		throw new ContextRejectedException(code);
	}
}
