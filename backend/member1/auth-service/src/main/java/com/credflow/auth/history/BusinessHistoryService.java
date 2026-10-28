package com.credflow.auth.history;

import java.util.*;
import java.time.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class BusinessHistoryService {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public BusinessHistoryService(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	private Map<String, Object> seller(String id, Jwt jwt, boolean lock) {
		if (jwt == null || jwt.getClaimAsStringList("roles") == null
				|| !jwt.getClaimAsStringList("roles").contains("MARKETPLACE")
				|| jwt.getClaimAsString("marketplaceId") == null || jwt.getClaimAsString("client_id") != null)
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Marketplace staff access required.");
		var rows = jdbc.queryForList(
				"SELECT SELLER_ID,MARKETPLACE_ID,MARKETPLACE_SELLER_ID,FULL_NAME,BUSINESS_NAME,BUSINESS_START_DATE FROM CF_SELLER_PROFILES WHERE SELLER_ID=? AND MARKETPLACE_ID=?"
						+ (lock ? " FOR UPDATE" : ""),
				id, jwt.getClaimAsString("marketplaceId"));
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller not found in your marketplace.");
		return rows.getFirst();
	}

	private List<Map<String, Object>> versions(String id) {
		return jdbc.queryForList(
				"SELECT RECORD_VERSION,BUSINESS_REVIEWED,DATA_ID,IDENTITY_FIXTURE_ID,TO_CHAR(SAVED_AT,'YYYY-MM-DD HH24:MI:SS TZH:TZM') AS SAVED_AT FROM CF_SELLER_BUSINESS_HISTORY WHERE SELLER_ID=? ORDER BY RECORD_VERSION DESC",
				id);
	}

	private JsonNode payload(String id, int version) {
		String raw = jdbc.queryForObject(
				"SELECT PAYLOAD_JSON FROM CF_SELLER_BUSINESS_HISTORY WHERE SELLER_ID=? AND RECORD_VERSION=?",
				String.class, id, version);
		return json.readTree(raw);
	}

	private JsonNode legacy(String id) {
		var maps = jdbc.queryForList(
				"SELECT SELLER_FIXTURE_ID FROM CF_CONSENT.CF_FINANCIAL_PROVIDER_MAP WHERE SELLER_ID=? AND SOURCE_PROVIDER='member2-financial-mock' AND MAPPING_STATUS='ACTIVE'",
				String.class, id);
		if (maps.isEmpty())
			return json.createObjectNode();
		var rows = jdbc.query("SELECT PAYLOAD FROM CF_SIM_FINANCIAL.CF_MOCK_FINANCIAL_RECORD WHERE RECORD_KEY=?",
				(rs, n) -> rs.getString(1), "profile:" + maps.getFirst());
		return rows.isEmpty() ? json.createObjectNode() : json.readTree(rows.getFirst());
	}

	private static YearMonth end() {
		return YearMonth.now(ZoneOffset.UTC);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> read(String id, Jwt jwt) {
		return present(seller(id, jwt, false));
	}

	private Map<String, Object> present(Map<String, Object> profile) {
		String id = profile.get("SELLER_ID").toString();
		var saved = versions(id);
		int version = saved.isEmpty() ? 0 : ((Number) saved.getFirst().get("RECORD_VERSION")).intValue();
		JsonNode data = version == 0 ? legacy(id) : payload(id, version);
		var months = new ArrayList<Object>();
		var end = end();
		for (int i = 3; i > 0; i--) {
			String month = end.minusMonths(i).toString();
			JsonNode found = null;
			for (var m : data.path("months"))
				if (month.equals(m.path("month").asText()))
					found = m;
			months.add(found == null ? Map.of("month", month, "status", "MISSING") : found);
		}
		boolean reviewed = !saved.isEmpty() && ((Number) saved.getFirst().get("BUSINESS_REVIEWED")).intValue() == 1;
		if (version == 0)
			reviewed = jdbc.queryForObject(
					"SELECT COUNT(*) FROM CF_CONSENT.CF_SELLER_IDENTITY_MAP WHERE SELLER_ID=? AND MARKETPLACE_ID=? AND MAPPING_STATUS='ACTIVE'",
					Integer.class, id, profile.get("MARKETPLACE_ID")) > 0;
		var result = new LinkedHashMap<String, Object>(
				Map.of("sellerId", id, "sellerReference", profile.get("MARKETPLACE_SELLER_ID"), "fullName",
						profile.get("FULL_NAME"), "businessName", profile.get("BUSINESS_NAME"), "businessStartDate",
						profile.get("BUSINESS_START_DATE").toString().substring(0, 10), "recordVersion", version,
						"businessDetailsReviewed", reviewed, "months", months, "lastSavedAt",
						saved.isEmpty() ? "" : saved.getFirst().get("SAVED_AT")));
		result.put("creditScoreBand", data.path("creditScoreBand").asText("NOT_AVAILABLE"));
		return result;
	}

	@Transactional
	public Map<String, Object> save(String id, BusinessHistoryInput input, String key, Jwt jwt) {
		var profile = seller(id, jwt, true);
		String market = profile.get("MARKETPLACE_ID").toString();
		try {
			UUID.fromString(key);
		} catch (Exception ex) {
			throw bad("A valid retry key is required.");
		}
		validate(input, LocalDate.parse(profile.get("BUSINESS_START_DATE").toString().substring(0, 10)), end());
		String canonical = json.writeValueAsString(Map.of("input", input, "actor", jwt.getSubject()));
		String hash = hash(canonical);
		var replay = jdbc.queryForList(
				"SELECT REQUEST_HASH FROM CF_SELLER_BUSINESS_HISTORY WHERE SELLER_ID=? AND IDEMPOTENCY_KEY=?",
				String.class, id, key);
		if (!replay.isEmpty()) {
			if (!hash.equals(replay.getFirst()))
				throw conflict("This retry key was used with different history.");
			return present(profile);
		}
		var saved = versions(id);
		int version = saved.isEmpty() ? 0 : ((Number) saved.getFirst().get("RECORD_VERSION")).intValue();
		if (input.expectedVersion() != version)
			throw conflict("Business history changed. Reload it before saving.");
		var months = new TreeMap<String, JsonNode>();
		JsonNode old = version == 0 ? legacy(id) : payload(id, version);
		for (var m : old.path("months"))
			months.put(m.path("month").asText(), m);
		for (var m : input.months()) {
			var node = json.createObjectNode();
			node.put("month", m.month());
			node.put("status", m.status());
			if ("AVAILABLE".equals(m.status())) {
				node.put("grossSalesAmount", money(m.grossSalesAmount()).toPlainString());
				node.put("returnsAmount", money(m.returnsAmount()).toPlainString());
				node.put("payoutAmount", money(m.payoutAmount()).toPlainString());
				node.put("orderCount", m.orderCount());
				node.put("returnedOrderCount", m.returnedOrderCount());
			}
			months.put(m.month(), node);
		}
		var source = json.createObjectNode();
		source.put("currency", "INR");
		source.put("creditScoreBand", input.creditScoreBand() == null ? "NOT_AVAILABLE" : input.creditScoreBand());
		source.put("businessName", profile.get("BUSINESS_NAME").toString());
		var array = source.putArray("months");
		months.values().forEach(array::add);
		String history = UUID.randomUUID().toString(), dataId = "MPH-" + history,
				identity = "DEMO-MARKETPLACE-" + history.toUpperCase(Locale.ROOT);
		jdbc.update(
				"INSERT INTO CF_SELLER_BUSINESS_HISTORY(HISTORY_ID,SELLER_ID,MARKETPLACE_ID,RECORD_VERSION,DATA_ID,IDENTITY_FIXTURE_ID,BUSINESS_REVIEWED,PAYLOAD_JSON,REQUEST_HASH,IDEMPOTENCY_KEY,SAVED_BY) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
				history, id, market, version + 1, dataId, identity,
				Boolean.TRUE.equals(input.businessDetailsReviewed()) ? 1 : 0, source.toString(), hash, key,
				jwt.getSubject());
		jdbc.update(
				"MERGE INTO CF_CONSENT.CF_FINANCIAL_PROVIDER_MAP D USING (SELECT ? SELLER_ID,? MARKETPLACE_ID FROM DUAL) S ON (D.SELLER_ID=S.SELLER_ID AND D.MARKETPLACE_ID=S.MARKETPLACE_ID AND D.SOURCE_PROVIDER='member2-financial-mock') WHEN MATCHED THEN UPDATE SET D.SELLER_FIXTURE_ID=?,D.MAPPING_STATUS='ACTIVE',D.UPDATED_AT=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT (SELLER_ID,MARKETPLACE_ID,SOURCE_PROVIDER,SELLER_FIXTURE_ID,MAPPING_STATUS) VALUES (S.SELLER_ID,S.MARKETPLACE_ID,'member2-financial-mock',?,'ACTIVE')",
				id, market, dataId, dataId);
		jdbc.update(
				"MERGE INTO CF_CONSENT.CF_SELLER_IDENTITY_MAP D USING (SELECT ? SELLER_ID,? MARKETPLACE_ID FROM DUAL) S ON (D.SELLER_ID=S.SELLER_ID AND D.MARKETPLACE_ID=S.MARKETPLACE_ID) WHEN MATCHED THEN UPDATE SET D.FIXTURE_IDENTITY_ID=?,D.MAPPING_STATUS='ACTIVE',D.UPDATED_AT=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT (SELLER_ID,MARKETPLACE_ID,FIXTURE_IDENTITY_ID,MAPPING_STATUS) VALUES (S.SELLER_ID,S.MARKETPLACE_ID,?,'ACTIVE')",
				id, market, identity, identity);
		return present(profile);
	}

	public static void validate(BusinessHistoryInput input, LocalDate start, YearMonth end) {
		if (input == null || input.expectedVersion() < 0 || input.businessDetailsReviewed() == null
				|| input.months() == null || input.months().size() != 3)
			throw bad("Enter the three completed months shown in the form.");
		var expected = new HashSet<String>();
		for (int i = 3; i > 0; i--)
			expected.add(end.minusMonths(i).toString());
		for (var m : input.months()) {
			if (m == null || !expected.remove(m.month())
					|| !Set.of("AVAILABLE", "MISSING").contains(Objects.toString(m.status(), "")))
				throw bad("Months must be unique and match the three completed calendar months.");
			if ("MISSING".equals(m.status())) {
				if (m.grossSalesAmount() != null || m.returnsAmount() != null || m.payoutAmount() != null
						|| m.orderCount() != null || m.returnedOrderCount() != null)
					throw bad("A missing month must not contain financial amounts or order counts.");
				continue;
			}
			if (YearMonth.parse(m.month()).atEndOfMonth().isBefore(start))
				throw bad("History cannot be entered before this business started.");
			var gross = money(m.grossSalesAmount());
			var returns = money(m.returnsAmount());
			money(m.payoutAmount());
			if (returns.compareTo(gross) > 0 || m.orderCount() == null || m.returnedOrderCount() == null
					|| m.orderCount() < 0 || m.orderCount() > 1000000000L || m.returnedOrderCount() < 0
					|| m.returnedOrderCount() > m.orderCount())
				throw bad("Returns cannot exceed sales, and returned orders cannot exceed total orders.");
		}
	}

	private static BigDecimal money(String v) {
		if (v == null || !v.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
			throw bad("Enter non-negative amounts with at most two decimal places.");
		return new BigDecimal(v).setScale(2);
	}

	private static String hash(String v) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ResponseStatusException bad(String m) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
	}

	private static ResponseStatusException conflict(String m) {
		return new ResponseStatusException(HttpStatus.CONFLICT, m);
	}
}
