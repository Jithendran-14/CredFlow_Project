package com.credflow.eligibility;

import java.util.*;
import java.time.*;
import java.math.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Phase0AssessmentTest {
	BigDecimal n(String v) {
		return new BigDecimal(v);
	}

	EligibilityCalculator calculator = new EligibilityCalculator();

	EligibilityCalculator.Product product() {
		return new EligibilityCalculator.Product("p", "l", 1, 6, n("10000"), n("20"), n("1000"), n("500000"), n("2"), 1,
				Set.of("WORKING_CAPITAL"), "FAIR", n("40"), n("18"));
	}

	EligibilityCalculator.Result evaluate(String credit, String expenses) {
		return calculator.evaluate(
				new EligibilityCalculator.Financials("EVALUATED", null, n("1500000"), n("30000"), n("1350000"), credit),
				LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 22), n("100000.00"), "WORKING_CAPITAL",
				List.of(product()), new EligibilityCalculator.Terms(12, n("500000"), n(expenses), n("10000")));
	}

	@Test
	void repaymentUsesPreferredTenureAndRate() {
		assertEquals(n("8884.88"), EligibilityCalculator.payment(n("100000"), 12, n("12")));
		assertEquals(n("8333.33"), EligibilityCalculator.payment(n("100000"), 12, n("0")));
		assertTrue(evaluate("GOOD", "100000").lenderResults().getFirst().eligible());
	}

	@Test
	void lowCreditAndInsufficientSurplusDoNotQualify() {
		assertTrue(evaluate("POOR", "100000").lenderResults().getFirst().reasonCodes()
				.contains("CREDIT_SCORE_BAND_BELOW_MINIMUM"));
		assertTrue(
				evaluate("GOOD", "440000").lenderResults().getFirst().reasonCodes().contains("AFFORDABILITY_NOT_MET"));
	}

	@Test
	void missingCreditRequestsActionInsteadOfRejecting() {
		var result = evaluate("NOT_AVAILABLE", "100000");
		assertEquals("ACTION_REQUIRED", result.outcome());
		assertEquals(List.of("CREDIT_INFORMATION_REQUIRED"), result.reasonCodes());
		assertTrue(result.lenderResults().isEmpty());
	}
}
