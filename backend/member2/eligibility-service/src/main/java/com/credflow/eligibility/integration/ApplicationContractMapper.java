package com.credflow.eligibility.integration;

import com.credflow.eligibility.EligibilityInputGuard;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Frozen target response mapping only; does not call or authenticate to the
 * pending APIs.
 */
@Component
public class ApplicationContractMapper {
	public record ApplicationDetails(EligibilityInputGuard.Application application, String sellerId) {
	}

	public static class ApplicationContractException extends IllegalArgumentException {
		private final String code;

		public ApplicationContractException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private final ObjectMapper json;

	public ApplicationContractMapper(ObjectMapper json) {
		this.json = Objects.requireNonNull(json);
	}

	public ApplicationDetails application(String payload, UUID expectedApplicationId, int expectedRevision,
			LocalDate asOfDate) {
		Objects.requireNonNull(expectedApplicationId);
		Objects.requireNonNull(asOfDate);
		if (expectedRevision < 1)
			throw new IllegalArgumentException("Expected revision must be positive");
		var value = read(payload);
		UUID id = uuid(value, "applicationId");
		var revision = value.path("applicationRevision");
		if (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue() < 1)
			throw invalid("Invalid application revision");
		if (!expectedApplicationId.equals(id) || expectedRevision != revision.intValue())
			throw conflict();
		String seller = null;
		if (!value.has("sellerId"))
			throw invalid("Application seller mapping field is missing");
		if (!value.path("sellerId").isNull())
			seller = text(value, "sellerId");
		var profile = value.path("businessProfile");
		if (!profile.isObject() || !profile.path("businessStartDate").isTextual())
			throw new ApplicationContractException("APPLICATION_DETAILS_INCOMPLETE",
					"Application business start date is not available");
		LocalDate start;
		try {
			start = LocalDate.parse(text(profile, "businessStartDate"));
		} catch (DateTimeException ex) {
			throw invalid("Invalid business start date");
		}
		if (start.isAfter(asOfDate))
			throw invalid("Business start date is after evaluation date");
		String amount = text(value, "requestedAmount");
		if (!amount.matches("[0-9]{1,17}(?:\\.[0-9]{1,2})?"))
			throw invalid("Invalid requested amount");
		BigDecimal requested = new BigDecimal(amount).setScale(2);
		if (requested.signum() <= 0 || !"INR".equals(text(value, "currency")))
			throw invalid("Invalid amount or currency");
		String purpose = text(value, "loanPurpose");
		if (!Set.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION").contains(purpose))
			throw invalid("Unknown loan purpose");
		var details = profile.path("phase0");
		com.credflow.eligibility.EligibilityCalculator.Terms terms = null;
		if (details.isObject())
			terms = new com.credflow.eligibility.EligibilityCalculator.Terms(
					details.path("preferredTenureMonths").asInt(),
					new BigDecimal(details.path("declaredMonthlyIncome").asText()),
					new BigDecimal(details.path("monthlyExpenses").asText()),
					new BigDecimal(details.path("existingMonthlyDebt").asText()));
		return new ApplicationDetails(
				new EligibilityInputGuard.Application(id, revision.intValue(), start, requested, "INR", purpose, terms),
				seller);
	}

	/**
	 * False is a valid denial, not a parsing failure. Identity must be derived from
	 * a verified seller JWT.
	 */
	public boolean ownsApplication(String payload, UUID expectedApplicationId, String expectedSellerId) {
		Objects.requireNonNull(expectedApplicationId);
		if (expectedSellerId == null || expectedSellerId.isBlank())
			throw new IllegalArgumentException("Trusted seller ID is required");
		var value = read(payload);
		if (!expectedApplicationId.equals(uuid(value, "applicationId")))
			throw conflict();
		// The final endpoint binds sellerId in our authenticated POST request and omits
		// it from its reply.
		if (value.has("sellerId") && !expectedSellerId.equals(text(value, "sellerId")))
			throw conflict();
		if (!value.path("ownsApplication").isBoolean())
			throw invalid("Ownership decision must be boolean");
		return value.path("ownsApplication").booleanValue();
	}

	private JsonNode read(String payload) {
		try {
			var node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(payload);
			if (node == null || !node.isObject())
				throw invalid("Application response must be an object");
			return node;
		} catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
			throw invalid("Invalid application response JSON");
		}
	}

	private String text(JsonNode node, String field) {
		var value = node.path(field);
		if (!value.isTextual() || value.textValue().isBlank())
			throw invalid("Missing application field: " + field);
		return value.textValue();
	}

	private UUID uuid(JsonNode node, String field) {
		String text = text(node, field);
		try {
			UUID id = UUID.fromString(text);
			if (!id.toString().equalsIgnoreCase(text))
				throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException ex) {
			throw invalid("Invalid application UUID");
		}
	}

	private static ApplicationContractException invalid(String message) {
		return new ApplicationContractException("INVALID_APPLICATION_RESPONSE", message);
	}

	private static ApplicationContractException conflict() {
		return new ApplicationContractException("APPLICATION_CONTEXT_MISMATCH",
				"Application response does not match the trusted context");
	}
}
