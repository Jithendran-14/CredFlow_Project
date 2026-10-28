package com.credflow.eligibility;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class EligibilityTestContext {
	static final Instant NOW = Instant.parse("2026-10-16T06:00:00Z");
	static final LocalDate AS_OF = LocalDate.of(2026, 10, 13);

	static EligibilityInputGuard.Request request() {
		return new EligibilityInputGuard.Request(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), AS_OF);
	}

	static FinancialImportService.Command source(EligibilityInputGuard.Request r, boolean missing) {
		var months = new ArrayList<FinancialImportService.MonthlyMetrics>();
		for (int i = 3; i >= 1; i--)
			months.add(new FinancialImportService.MonthlyMetrics(YearMonth.from(AS_OF).minusMonths(i),
					FinancialImportService.Coverage.COMPLETE, new BigDecimal("600000.00"), new BigDecimal("50000.00"),
					new BigDecimal("530000.00"), 600L, missing ? null : 50L));
		return new FinancialImportService.Command(r.applicationId(), r.applicationRevision(), r.asOfDate(),
				"VERIFICATION_MOCK", "FIN-" + r.applicationId(), 1, NOW.minusSeconds(60), "INR", months);
	}

	static EligibilityInputGuard.Context context(EligibilityInputGuard.Request r) {
		return new EligibilityInputGuard.Context(
				new EligibilityInputGuard.Application(r.applicationId(), r.applicationRevision(),
						LocalDate.of(2024, 1, 1), new BigDecimal("500000.00"), "INR", "WORKING_CAPITAL"),
				new EligibilityInputGuard.Verification(r.verificationId(), r.applicationId(), r.applicationRevision(),
						"VERIFIED"),
				new EligibilityInputGuard.Consent(r.applicationId(), r.applicationRevision(), "ACTIVE",
						"FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY", NOW.plusSeconds(600)),
				source(r, false));
	}

	static EligibilityCompletionOutbox.Metadata metadata() {
		return new EligibilityCompletionOutbox.Metadata("member2-local-demo", 1,
				new EligibilityCompletionOutbox.Actor("SYSTEM", "eligibility-worker", null),
				new EligibilityCompletionOutbox.Actor("SYSTEM_CLIENT", "orchestration-service-client", null), null);
	}
}
