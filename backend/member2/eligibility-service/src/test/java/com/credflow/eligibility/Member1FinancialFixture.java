package com.credflow.eligibility;

import com.credflow.eligibility.integration.FinancialDataContractMapper;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

final class Member1FinancialFixture {
	static final ObjectMapper JSON = new ObjectMapper();
	static final FinancialDataContractMapper MAPPER = new FinancialDataContractMapper(JSON,
			Clock.fixed(EligibilityTestContext.NOW, ZoneOffset.UTC));

	static ObjectNode sample() {
		try (var stream = Member1FinancialFixture.class
				.getResourceAsStream("/contracts/member1/financial-data-v1.json")) {
			return (ObjectNode) JSON.readTree(Objects.requireNonNull(stream));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static ObjectNode forRequest(EligibilityInputGuard.Request request) {
		var value = sample();
		value.put("applicationId", request.applicationId().toString());
		value.put("verificationId", request.verificationId().toString());
		value.put("applicationRevision", request.applicationRevision());
		return value;
	}

	static FinancialDataContractMapper.Expected expected(ObjectNode value) {
		return new FinancialDataContractMapper.Expected(UUID.fromString(value.path("applicationId").asText()),
				value.path("applicationRevision").asInt(), UUID.fromString(value.path("verificationId").asText()),
				"SELLER-1001", EligibilityTestContext.AS_OF);
	}

	static ObjectNode full(ObjectNode value) {
		for (var child : value.path("financialData").path("monthlyMetrics")) {
			var m = (ObjectNode) child;
			m.put("dataStatus", "AVAILABLE");
			m.put("sales", "600000.00");
			m.put("returns", "50000.00");
			m.put("payouts", "530000.00");
			m.put("orderCount", 600);
			m.put("returnedOrderCount", 50);
		}
		return value;
	}

	static ObjectNode month(ObjectNode value, int index) {
		return (ObjectNode) value.path("financialData").path("monthlyMetrics").get(index);
	}
}
