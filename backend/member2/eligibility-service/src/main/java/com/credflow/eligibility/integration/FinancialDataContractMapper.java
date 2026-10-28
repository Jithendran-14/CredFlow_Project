package com.credflow.eligibility.integration;

import com.credflow.eligibility.EligibilityInputGuard;
import com.credflow.eligibility.FinancialImportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps Member 1 v1 financial JSON; HTTP authentication and authoritative
 * identity status remain separate.
 */
@Component
public class FinancialDataContractMapper {
	public record Expected(UUID applicationId, int applicationRevision, UUID verificationId, String sellerId,
			LocalDate asOfDate) {
	}

	public record ConsentEvidence(String consentId, Instant authorisedAt, Instant expiresAt) {
	}

	public record Mapped(String sellerId, UUID verificationId, EligibilityInputGuard.Consent consent,
			ConsentEvidence consentEvidence, FinancialImportService.Command financialData) {
	}

	public static class FinancialContractException extends IllegalArgumentException {
		private final String code;

		public FinancialContractException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private final ObjectMapper json;
	private final Clock clock;

	@Autowired
	public FinancialDataContractMapper(ObjectMapper json) {
		this(json, Clock.systemUTC());
	}

	public FinancialDataContractMapper(ObjectMapper json, Clock clock) {
		this.json = Objects.requireNonNull(json);
		this.clock = Objects.requireNonNull(clock);
	}

	public Mapped map(String payload, Expected expected) {
		Objects.requireNonNull(expected, "Trusted expected context is required");
		Objects.requireNonNull(expected.applicationId());
		Objects.requireNonNull(expected.verificationId());
		Objects.requireNonNull(expected.asOfDate());
		if (expected.applicationRevision() < 1 || expected.sellerId() == null || expected.sellerId().isBlank())
			throw new IllegalArgumentException("Trusted application revision and seller are required");
		JsonNode response;
		try {
			// Reject trailing JSON and duplicate keys rather than accepting ambiguous
			// identifiers.
			response = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(payload);
		} catch (JsonProcessingException | IllegalArgumentException ex) {
			throw invalid("Financial response must be one valid JSON object");
		}
		object(response);
		UUID app = uuid(response, "applicationId"), verification = uuid(response, "verificationId");
		int revision = positiveInt(response, "applicationRevision"),
				version = positiveInt(response, "financialDataVersion");
		String seller = text(response, "sellerId", 200), reference = text(response, "financialDataReference", 200);
		if (!app.equals(expected.applicationId()) || revision != expected.applicationRevision()
				|| !verification.equals(expected.verificationId()) || !seller.equals(expected.sellerId()))
			throw conflict("Financial response does not match the expected application, seller or verification");

		var consent = object(response.path("consent"));
		String consentId = text(consent, "consentId", 200), status = text(consent, "status", 40),
				scope = text(consent, "scope", 100);
		Instant authorised = timestamp(consent, "authorisedAt"), expires = timestamp(consent, "expiresAt"),
				now = clock.instant();
		if (!"ACTIVE".equals(status) || !"FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY".equals(scope) || authorised.isAfter(now)
				|| !expires.isAfter(now) || !expires.isAfter(authorised))
			throw new FinancialContractException("FINANCIAL_CONSENT_NOT_ACTIVE",
					"Response does not contain active financial consent");
		var provenance = object(response.path("source"));
		String provider = text(provenance, "provider", 100);
		Instant retrieved = timestamp(provenance, "retrievedAt");
		if (retrieved.isAfter(now) || retrieved.isBefore(authorised) || !retrieved.isBefore(expires))
			throw invalid("Financial retrieval timestamp is inconsistent with consent");

		var data = object(response.path("financialData"));
		if (!"INR".equals(text(data, "currency", 3)) || positiveInt(data, "periodMonths") != 3
				|| !data.path("excludesCurrentMonth").isBoolean() || !data.path("excludesCurrentMonth").booleanValue())
			throw invalid("Financial response must contain three complete months in INR");
		var metrics = data.path("monthlyMetrics");
		if (!metrics.isArray() || metrics.size() != 3)
			throw invalid("All three months must be explicitly represented");
		YearMonth end = YearMonth.from(expected.asOfDate()).minusMonths(1), start = end.minusMonths(2);
		Map<YearMonth, FinancialImportService.MonthlyMetrics> months = new TreeMap<>();
		for (var row : metrics) {
			object(row);
			String monthValue = text(row, "month", 7);
			YearMonth month;
			try {
				if (!monthValue.matches("[0-9]{4}-[0-9]{2}"))
					throw new DateTimeException("Invalid month");
				month = YearMonth.parse(monthValue);
			} catch (DateTimeException ex) {
				throw invalid("Month must use YYYY-MM");
			}
			if (month.isBefore(start) || month.isAfter(end))
				throw conflict("Financial months do not match the expected evaluation window");
			String dataStatus = text(row, "dataStatus", 20);
			FinancialImportService.MonthlyMetrics mapped;
			if ("MISSING".equals(dataStatus)) {
				for (String name : List.of("sales", "returns", "payouts", "orderCount", "returnedOrderCount"))
					if (!row.has(name) || !row.path(name).isNull())
						throw invalid("MISSING months must explicitly contain null metrics");
				mapped = new FinancialImportService.MonthlyMetrics(month, FinancialImportService.Coverage.INCOMPLETE,
						null, null, null, null, null);
			} else if ("AVAILABLE".equals(dataStatus)) {
				// Inconsistent returns/counts are retained for the domain's explicit
				// ACTION_REQUIRED rule.
				mapped = new FinancialImportService.MonthlyMetrics(month, FinancialImportService.Coverage.COMPLETE,
						money(row, "sales"), money(row, "returns"), money(row, "payouts"), count(row, "orderCount"),
						count(row, "returnedOrderCount"));
			} else
				throw invalid("Unknown monthly dataStatus");
			if (months.putIfAbsent(month, mapped) != null)
				throw invalid("Duplicate financial month");
		}
		var command = new FinancialImportService.Command(app, revision, expected.asOfDate(), provider, reference,
				version, retrieved, "INR", List.copyOf(months.values()));
		return new Mapped(seller, verification,
				new EligibilityInputGuard.Consent(app, revision, status, scope, expires),
				new ConsentEvidence(consentId, authorised, expires), command);
	}

	/**
	 * Final 19 October Consent facade. Call only after authenticated HTTP retrieval
	 * for this request.
	 */
	public Mapped mapFinal(String payload, Expected expected) {
		Objects.requireNonNull(expected);
		Objects.requireNonNull(expected.asOfDate());
		if (expected.sellerId() == null || expected.sellerId().isBlank())
			throw invalid("Trusted seller mapping is required");
		JsonNode r;
		try {
			r = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(payload);
		} catch (Exception ex) {
			throw invalid("Invalid financial response JSON");
		}
		object(r);
		UUID app = uuid(r, "applicationId"), verification = uuid(r, "verificationId");
		int revision = positiveInt(r, "applicationRevision"), version = positiveInt(r, "dataVersion");
		if (!app.equals(expected.applicationId()) || revision != expected.applicationRevision()
				|| !verification.equals(expected.verificationId()))
			throw conflict("Financial response is for a different application, revision or verification");
		Instant now = clock.instant();
		var identity = object(r.path("identityVerification"));
		if (!"VERIFIED".equals(text(identity, "status", 30)) || timestamp(identity, "checkedAt").isAfter(now))
			throw conflict("Financial response does not attest a completed identity check");
		var consent = object(r.path("financialConsent"));
		String consentId = uuid(consent, "consentId").toString();
		positiveInt(consent, "consentVersion");
		if (!"ACTIVE".equals(text(consent, "status", 30))
				|| !"FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY".equals(text(consent, "scope", 100)))
			throw new FinancialContractException("FINANCIAL_CONSENT_NOT_ACTIVE", "Financial consent is not active");
		var d = object(r.path("financialData"));
		YearMonth end = YearMonth.from(expected.asOfDate()), start = end.minusMonths(3);
		if (!expected.asOfDate().toString().equals(text(d, "asOfDate", 10)) || positiveInt(d, "periodMonths") != 3
				|| !start.atDay(1).toString().equals(text(d, "periodStart", 10))
				|| !end.atDay(1).toString().equals(text(d, "periodEndExclusive", 10))
				|| !"INR".equals(text(d, "currency", 3)))
			throw conflict("Financial window must be the three completed calendar months");
		var rows = d.path("months");
		if (!rows.isArray() || rows.size() != 3)
			throw invalid("Three explicit financial months are required");
		var metrics = new ArrayList<FinancialImportService.MonthlyMetrics>();
		int index = 0;
		for (JsonNode row : rows) {
			object(row);
			YearMonth month = start.plusMonths(index++);
			if (!month.toString().equals(text(row, "month", 7)))
				throw conflict("Financial months must be complete and ordered");
			String status = text(row, "status", 20);
			if (status.equals("MISSING")) {
				for (String field : List.of("grossSalesAmount", "returnsAmount", "payoutAmount", "orderCount",
						"returnedOrderCount"))
					if (row.hasNonNull(field))
						throw invalid("Missing month cannot contain numeric values");
				metrics.add(new FinancialImportService.MonthlyMetrics(month, FinancialImportService.Coverage.INCOMPLETE,
						null, null, null, null, null));
			} else if (status.equals("AVAILABLE")) {
				metrics.add(new FinancialImportService.MonthlyMetrics(month, FinancialImportService.Coverage.COMPLETE,
						money(row, "grossSalesAmount"), money(row, "returnsAmount"), money(row, "payoutAmount"),
						count(row, "orderCount"), count(row, "returnedOrderCount")));
			} else
				throw invalid("Unknown financial month status");
		}
		Instant retrieved = timestamp(r, "retrievedAt");
		if (retrieved.isAfter(now))
			throw invalid("Financial retrieval time is in the future");
		var command = new FinancialImportService.Command(app, revision, expected.asOfDate(),
				text(r, "sourceProvider", 100), text(r, "dataReference", 200), version, retrieved, "INR",
				List.copyOf(metrics), d.path("creditScoreBand").asText("NOT_AVAILABLE"));
		// The facade rechecks live permission before returning; no expiry date is
		// fabricated.
		return new Mapped(
				expected.sellerId(), verification, new EligibilityInputGuard.Consent(app, revision, "ACTIVE",
						"FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY", null, now),
				new ConsentEvidence(consentId, null, null), command);
	}

	private JsonNode object(JsonNode node) {
		if (node == null || !node.isObject())
			throw invalid("Required financial object is missing or invalid");
		return node;
	}

	private String text(JsonNode node, String field, int maxLength) {
		var value = node.path(field);
		if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength)
			throw invalid("Required financial field is missing or invalid: " + field);
		return value.textValue();
	}

	private UUID uuid(JsonNode node, String field) {
		String value = text(node, field, 36);
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value))
				throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException ex) {
			throw invalid("Financial identifier must be a UUID: " + field);
		}
	}

	private int positiveInt(JsonNode node, String field) {
		var value = node.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1)
			throw invalid("Financial version/count must be a positive integer: " + field);
		return value.intValue();
	}

	private Instant timestamp(JsonNode node, String field) {
		try {
			return OffsetDateTime.parse(text(node, field, 100)).toInstant();
		} catch (DateTimeException ex) {
			throw invalid("Financial timestamp must include an offset: " + field);
		}
	}

	private BigDecimal money(JsonNode node, String field) {
		String value = text(node, field, 20);
		if (!value.matches("[0-9]{1,17}(?:\\.[0-9]{1,2})?"))
			throw invalid("Money must be a nonnegative decimal string: " + field);
		return new BigDecimal(value).setScale(2);
	}

	private long count(JsonNode node, String field) {
		var value = node.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
				|| value.longValue() > 9_999_999_999L)
			throw invalid("Monthly count is missing or outside storage range: " + field);
		return value.longValue();
	}

	private static FinancialContractException invalid(String message) {
		return new FinancialContractException("INVALID_FINANCIAL_RESPONSE", message);
	}

	private static FinancialContractException conflict(String message) {
		return new FinancialContractException("FINANCIAL_CONTEXT_MISMATCH", message);
	}
}
