package com.credflow.eligibility;

import com.credflow.eligibility.integration.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FinalFinancialContractTest {
	final ObjectMapper json = new ObjectMapper();
	final UUID app = UUID.randomUUID(), verification = UUID.randomUUID();
	final Instant now = Instant.parse("2026-10-19T09:00:00Z");
	final LocalDate date = LocalDate.parse("2026-10-19");
	final FinancialDataContractMapper mapper = new FinancialDataContractMapper(json, Clock.fixed(now, ZoneOffset.UTC));

	FinancialDataContractMapper.Expected expected() {
		return new FinancialDataContractMapper.Expected(app, 1, verification, "SELLER-1001", date);
	}

	ObjectNode response() {
		var r = json.createObjectNode();
		r.put("applicationId", app.toString());
		r.put("applicationRevision", 1);
		r.put("verificationId", verification.toString());
		r.put("sourceProvider", "member2-financial-mock");
		r.put("dataReference", "fixture-reference");
		r.put("dataVersion", 1);
		r.put("retrievedAt", "2026-10-19T08:00:00Z");
		r.putObject("identityVerification").put("status", "VERIFIED").put("checkedAt", "2026-10-19T07:00:00Z");
		r.putObject("financialConsent").put("consentId", UUID.randomUUID().toString()).put("consentVersion", 1)
				.put("status", "ACTIVE").put("scope", "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY");
		var d = r.putObject("financialData");
		d.put("asOfDate", date.toString());
		d.put("periodMonths", 3);
		d.put("periodStart", "2026-07-01");
		d.put("periodEndExclusive", "2026-10-01");
		d.put("currency", "INR");
		var months = d.putArray("months");
		for (String month : List.of("2026-07", "2026-08", "2026-09"))
			months.addObject().put("month", month).put("status", "AVAILABLE").put("grossSalesAmount", "100000.00")
					.put("returnsAmount", "1000.00").put("payoutAmount", "90000.00").put("orderCount", 100)
					.put("returnedOrderCount", 1);
		return r;
	}

	@Test
	void acceptsFinalShapeWithoutInventingConsentExpiry() {
		var m = mapper.mapFinal(response().toString(), expected());
		assertThat(m.consent().expiresAt()).isNull();
		assertThat(m.consent().authorityCheckedAt()).isEqualTo(now);
		assertThat(m.financialData().months()).hasSize(3);
	}

	@Test
	void missingMonthCanOmitMetrics() {
		var r = response();
		var row = (ObjectNode) r.path("financialData").path("months").get(1);
		row.removeAll();
		row.put("month", "2026-08").put("status", "MISSING");
		assertThat(mapper.mapFinal(r.toString(), expected()).financialData().months().get(1).coverage())
				.isEqualTo(FinancialImportService.Coverage.INCOMPLETE);
	}

	@Test
	void missingMonthCannotHideSales() {
		var r = response();
		((ObjectNode) r.path("financialData").path("months").get(1)).put("status", "MISSING");
		assertThatThrownBy(() -> mapper.mapFinal(r.toString(), expected()))
				.isInstanceOf(FinancialDataContractMapper.FinancialContractException.class);
	}

	@Test
	void rejectsAnotherRevision() {
		var r = response().put("applicationRevision", 2);
		assertThatThrownBy(() -> mapper.mapFinal(r.toString(), expected()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsUnverifiedIdentity() {
		var r = response();
		((ObjectNode) r.path("identityVerification")).put("status", "FAILED");
		assertThatThrownBy(() -> mapper.mapFinal(r.toString(), expected()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsCurrentMonthAndWrongWindow() {
		var r = response();
		((ObjectNode) r.path("financialData")).put("periodEndExclusive", "2026-11-01");
		assertThatThrownBy(() -> mapper.mapFinal(r.toString(), expected()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void finalOwnershipDoesNotRequireAnInventedEcho() {
		assertThat(new ApplicationContractMapper(json)
				.ownsApplication("{\"applicationId\":\"" + app + "\",\"ownsApplication\":true}", app, "SELLER-1001"))
				.isTrue();
	}
}
