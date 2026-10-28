package com.credflow.eligibility;

import com.credflow.eligibility.api.EligibilityResultResponse;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EligibilityResultResponseTest {
	final ObjectMapper json = new ObjectMapper();

	static BigDecimal b(String s) {
		return new BigDecimal(s);
	}

	EligibilityCalculator.Result good(boolean eligible) {
		return new EligibilityCalculator.Result("EVALUATED", List.of(), b("550000"), b("530000"), b("8.33"),
				List.of(new EligibilityCalculator.LenderResult("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
						"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1", 1, b("550000"), eligible,
						eligible ? List.of() : List.of("PURPOSE_NOT_SUPPORTED"), 1)));
	}

	EligibilityEvaluationService.StoredEvaluation saved(EligibilityCalculator.Result r) {
		return new EligibilityEvaluationService.StoredEvaluation(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), "1.0",
				OffsetDateTime.parse("2026-10-15T15:00:00+05:30"), b("500000"), "INR", "WORKING_CAPITAL",
				LocalDate.of(2024, 1, 1), LocalDate.of(2026, 10, 13), r);
	}

	JsonNode output(EligibilityCalculator.Result r) {
		return json.valueToTree(EligibilityResultResponse.from(saved(r)));
	}

	@Test
	void moneyIsAStringWithTwoDecimalsAndFieldsAreFlat() {
		var n = output(good(true));
		assertThat(n.path("requestedAmount").isTextual()).isTrue();
		assertThat(n.path("requestedAmount").asText()).isEqualTo("500000.00");
		assertThat(n.path("averageMonthlyNetSales").asText()).isEqualTo("550000.00");
		assertThat(n.path("lenderResults").get(0).path("indicativeMaxAmount").asText()).isEqualTo("550000.00");
		assertThat(n.path("lenderResults").get(0).path("ruleVersion").isTextual()).isTrue();
		assertThat(n.has("result")).isFalse();
		assertThat(n.has("requestHash")).isFalse();
	}

	@Test
	void datesUseTheDraftUtcAndCalendarFormats() {
		var n = output(good(true));
		assertThat(n.path("evaluatedAt").asText()).isEqualTo("2026-10-15T09:30:00Z");
		assertThat(n.path("asOfDate").asText()).isEqualTo("2026-10-13");
	}

	@Test
	void actionRequiredOmitsUnknownFiguresRatherThanSendingZeroOrNull() {
		var n = output(new EligibilityCalculator.Result("ACTION_REQUIRED", List.of("FINANCIAL_DATA_INCOMPLETE"), null,
				null, null, List.of()));
		assertThat(n.has("averageMonthlyNetSales")).isFalse();
		assertThat(n.has("averageMonthlyPayout")).isFalse();
		assertThat(n.has("returnRatePercent")).isFalse();
		assertThat(n.path("lenderResults").isEmpty()).isTrue();
		assertThat(n.path("reasonCodes").get(0).asText()).isEqualTo("FINANCIAL_DATA_INCOMPLETE");
	}

	@Test
	void failedProductKeepsItsExplanationAndBooleanValue() {
		var n = output(good(false)).path("lenderResults").get(0);
		assertThat(n.path("eligible").isBoolean()).isTrue();
		assertThat(n.path("eligible").booleanValue()).isFalse();
		assertThat(n.path("reasonCodes").get(0).asText()).isEqualTo("PURPOSE_NOT_SUPPORTED");
	}

	@Test
	void invalidInternalActionCannotProduceMisleadingFrontendFigures() {
		var bad = new EligibilityCalculator.Result("ACTION_REQUIRED", List.of(), b("0"), b("0"), b("0"), List.of());
		assertThatThrownBy(() -> EligibilityResultResponse.from(saved(bad)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void adapterNeverSilentlyRoundsAnUnexpectedValue() {
		var bad = new EligibilityCalculator.Result("EVALUATED", List.of(), b("550000.001"), b("530000"), b("8.33"),
				List.of());
		assertThatThrownBy(() -> EligibilityResultResponse.from(saved(bad))).isInstanceOf(ArithmeticException.class);
	}
}
