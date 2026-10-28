package com.credflow.eligibility;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class EligibilityInputGuardTest {
	final EligibilityInputGuard guard = new EligibilityInputGuard(
			Clock.fixed(EligibilityTestContext.NOW, ZoneOffset.UTC));
	final EligibilityInputGuard.Request request = EligibilityTestContext.request();
	final EligibilityInputGuard.Context context = EligibilityTestContext.context(request);

	void rejected(EligibilityInputGuard.Context c, String code) {
		assertThatThrownBy(() -> guard.validate(request, c))
				.isInstanceOf(EligibilityInputGuard.ContextRejectedException.class).hasMessage(code);
	}

	@Test
	void matchingAuthorisedInputsAreAccepted() {
		assertThatCode(() -> guard.validate(request, context)).doesNotThrowAnyException();
	}

	@Test
	void absentTrustedContextIsNotAccepted() {
		rejected(null, "TRUSTED_CONTEXT_MISSING");
	}

	@Test
	void correctedApplicationCannotUseEarlierCheck() {
		var r = new EligibilityInputGuard.Request(request.evaluationRequestId(), request.applicationId(), 2,
				request.verificationId(), request.asOfDate());
		assertThatThrownBy(() -> guard.validate(r, context)).hasMessage("APPLICATION_CONTEXT_MISMATCH");
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "PENDING", "ACTION_REQUIRED" })
	void identityMustHavePassed(String status) {
		var v = context.verification();
		rejected(
				new EligibilityInputGuard.Context(context.application(),
						new EligibilityInputGuard.Verification(v.verificationId(), v.applicationId(), v.revision(),
								status),
						context.consent(), context.financialData()),
				"VERIFICATION_NOT_CURRENT_OR_VERIFIED");
	}

	@Test
	void wrongVerificationReferenceRejected() {
		var v = context.verification();
		rejected(
				new EligibilityInputGuard.Context(context.application(),
						new EligibilityInputGuard.Verification(UUID.randomUUID(), v.applicationId(), v.revision(),
								v.status()),
						context.consent(), context.financialData()),
				"VERIFICATION_NOT_CURRENT_OR_VERIFIED");
	}

	@Test
	void anotherSellersDataCannotBeUsed() {
		var c = context.consent();
		rejected(new EligibilityInputGuard.Context(context.application(), context.verification(),
				new EligibilityInputGuard.Consent(UUID.randomUUID(), c.revision(), c.status(), c.scope(),
						c.expiresAt()),
				context.financialData()), "APPLICATION_CONTEXT_MISMATCH");
	}

	@ParameterizedTest
	@ValueSource(strings = { "REVOKED", "DECLINED", "EXPIRED" })
	void inactiveConsentRejected(String status) {
		var c = context.consent();
		rejected(new EligibilityInputGuard.Context(context.application(), context.verification(),
				new EligibilityInputGuard.Consent(c.applicationId(), c.revision(), status, c.scope(), c.expiresAt()),
				context.financialData()), "FINANCIAL_CONSENT_NOT_ACTIVE");
	}

	@Test
	void consentExpiryBoundaryRejected() {
		var c = context.consent();
		rejected(new EligibilityInputGuard.Context(context.application(), context.verification(),
				new EligibilityInputGuard.Consent(c.applicationId(), c.revision(), c.status(), c.scope(),
						EligibilityTestContext.NOW),
				context.financialData()), "FINANCIAL_CONSENT_NOT_ACTIVE");
	}

	@Test
	void sharingConsentDoesNotGrantFinancialRetrieval() {
		var c = context.consent();
		rejected(
				new EligibilityInputGuard.Context(context.application(), context.verification(),
						new EligibilityInputGuard.Consent(c.applicationId(), c.revision(), c.status(),
								"APPLICATION_SHARE_WITH_CREDFLOW", c.expiresAt()),
						context.financialData()),
				"FINANCIAL_CONSENT_NOT_ACTIVE");
	}

	@Test
	void anotherEvaluationDateRejected() {
		var f = context.financialData();
		var changed = new FinancialImportService.Command(f.applicationId(), f.applicationRevision(),
				f.asOfDate().minusMonths(1), f.sourceProvider(), f.dataReference(), f.dataVersion(), f.retrievedAt(),
				f.currency(), f.months());
		rejected(new EligibilityInputGuard.Context(context.application(), context.verification(), context.consent(),
				changed), "FINANCIAL_CONTEXT_MISMATCH");
	}

	@Test
	void futureRetrievedDataRejected() {
		var f = context.financialData();
		var changed = new FinancialImportService.Command(f.applicationId(), f.applicationRevision(), f.asOfDate(),
				f.sourceProvider(), f.dataReference(), f.dataVersion(), EligibilityTestContext.NOW.plusSeconds(1),
				f.currency(), f.months());
		rejected(new EligibilityInputGuard.Context(context.application(), context.verification(), context.consent(),
				changed), "FINANCIAL_CONTEXT_MISMATCH");
	}
}
