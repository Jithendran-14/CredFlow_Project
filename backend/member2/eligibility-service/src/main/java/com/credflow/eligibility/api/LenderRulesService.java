package com.credflow.eligibility.api;

import com.credflow.platform.persistence.DatabaseAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class LenderRulesService {
	private final DatabaseAccess db;
	private final ObjectMapper json;

	public LenderRulesService(DatabaseAccess db, ObjectMapper json) {
		this.db = db;
		this.json = json;
	}

	static String lender(Jwt jwt) {
		if (jwt == null || jwt.getClaimAsStringList("roles") == null
				|| !jwt.getClaimAsStringList("roles").contains("LENDER_OFFICER")
				|| jwt.getClaimAsStringList("roles").contains("ADMIN") || jwt.getClaimAsString("client_id") != null)
			throw new ResponseStatusException(FORBIDDEN, "Lender officer access required.");
		String id = jwt.getClaimAsString("lenderId");
		try {
			return UUID.fromString(id).toString();
		} catch (Exception ex) {
			throw new ResponseStatusException(FORBIDDEN, "A valid lender identity is required.");
		}
	}

	private void owned(UUID id, String lender) {
		if (db.queryForList("SELECT product_id FROM lender_product_rule WHERE product_id=? AND lender_id=?",
				String.class, id.toString(), lender).isEmpty())
			throw new ResponseStatusException(NOT_FOUND, "Lender product not found.");
	}

	private Map<String, Object> present(Map<String, Object> r) {
		var out = new LinkedHashMap<String, Object>();
		for (var pair : List.of("PRODUCT_ID:productId", "LENDER_ID:lenderId", "PRODUCT_NAME:productName",
				"RULE_VERSION:version", "MIN_AGE_MONTHS:minAgeMonths", "MIN_AVERAGE_NET:minAverageNet",
				"MAX_RETURN_PERCENT:maxReturnPercent", "MIN_AMOUNT:minAmount", "MAX_AMOUNT:maxAmount",
				"SALES_MULTIPLIER:salesMultiplier", "MIN_CREDIT_SCORE_BAND:minCreditScoreBand",
				"MAX_REPAYMENT_PERCENT:maxRepaymentPercent", "ASSESSMENT_APR_PERCENT:assessmentAprPercent")) {
			var p = pair.split(":");
			var value = r.get(p[0]);
			out.put(p[1], value instanceof BigDecimal b
					? (Set.of("RULE_VERSION", "MIN_AGE_MONTHS", "ROUTING_PRIORITY").contains(p[0]) ? b.intValueExact()
							: b.toPlainString())
					: value);
		}
		out.put("active", ((Number) r.get("ACTIVE")).intValue() == 1);
		out.put("purposes", db.queryForList(
				"SELECT purpose FROM lender_product_purpose WHERE product_id=? AND rule_version=? ORDER BY purpose",
				String.class, r.get("PRODUCT_ID"), r.get("RULE_VERSION")));
		return out;
	}

	@Transactional(readOnly = true)
	public Object list(Jwt jwt) {
		String lender = lender(jwt);
		return Map.of("items", db.queryForList(
				"SELECT R.* FROM lender_product_rule R JOIN lender_product_head H ON H.product_id=R.product_id AND H.current_version=R.rule_version WHERE R.lender_id=? ORDER BY R.routing_priority,R.product_id",
				lender).stream().map(this::present).toList());
	}

	@Transactional(readOnly = true)
	public Object history(UUID id, Jwt jwt) {
		String lender = lender(jwt);
		owned(id, lender);
		return Map.of("items", db.queryForList(
				"SELECT R.*,C.ACTOR_ID,C.CHANGED_AT,C.REASON FROM lender_product_rule R LEFT JOIN lender_rule_change C ON C.product_id=R.product_id AND C.rule_version=R.rule_version WHERE R.product_id=? AND (C.REASON IS NULL OR C.REASON NOT LIKE '[ADMIN_ROUTING] %') ORDER BY R.rule_version DESC",
				id.toString()).stream().map(r -> {
					var v = present(r);
					v.put("actorId", r.get("ACTOR_ID"));
					v.put("changedAt", Objects.toString(r.get("CHANGED_AT"), null));
					v.put("reason", r.get("REASON"));
					return v;
				}).toList());
	}

	public Object update(UUID id, LenderRulesInput input, String key, Jwt jwt) {
		String lender = lender(jwt);
		if (input.routingPriority() != null)
			throw new ResponseStatusException(FORBIDDEN, "Routing priority can only be set by an administrator.");
		owned(id, lender);
		try {
			UUID.fromString(key);
		} catch (Exception ex) {
			throw new ResponseStatusException(BAD_REQUEST, "A valid retry key is required.");
		}
		validate(input);
		String retry = jwt.getSubject() + ":" + key, hash = hash(input);
		var lock = db.queryForList("SELECT current_version FROM lender_product_head WHERE product_id=? FOR UPDATE",
				Integer.class, id.toString());
		if (lock.isEmpty())
			throw new ResponseStatusException(NOT_FOUND, "Lender product not found.");
		var replay = db.queryForList(
				"SELECT PRODUCT_ID,RULE_VERSION,REQUEST_HASH FROM lender_rule_change WHERE retry_key=?", retry);
		if (!replay.isEmpty()) {
			var r = replay.getFirst();
			if (!id.toString().equals(r.get("PRODUCT_ID")) || !hash.equals(r.get("REQUEST_HASH")))
				throw new ResponseStatusException(CONFLICT, "Retry key was used for different rules.");
			return present(db.queryForMap("SELECT * FROM lender_product_rule WHERE product_id=? AND rule_version=?",
					id.toString(), r.get("RULE_VERSION")));
		}
		int current = lock.getFirst();
		if (current != input.expectedVersion())
			throw new ResponseStatusException(CONFLICT, "Rules changed. Refresh before saving.");
		var old = db.queryForMap("SELECT * FROM lender_product_rule WHERE product_id=? AND rule_version=?",
				id.toString(), current);
		int version = current + 1;
		db.update(
				"INSERT INTO lender_product_rule(product_id,rule_version,lender_id,product_name,min_age_months,min_average_net,max_return_percent,min_amount,max_amount,sales_multiplier,routing_priority,active,min_credit_score_band,max_repayment_percent,assessment_apr_percent) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
				id.toString(), version, old.get("LENDER_ID"), input.productName().trim(), input.minAgeMonths(),
				new BigDecimal(input.minAverageNet()), new BigDecimal(input.maxReturnPercent()),
				new BigDecimal(input.minAmount()), new BigDecimal(input.maxAmount()),
				new BigDecimal(input.salesMultiplier()), old.get("ROUTING_PRIORITY"), input.active() ? 1 : 0,
				input.minCreditScoreBand(), new BigDecimal(input.maxRepaymentPercent()),
				new BigDecimal(input.assessmentAprPercent()));
		for (String purpose : input.purposes())
			db.update("INSERT INTO lender_product_purpose(product_id,rule_version,purpose) VALUES (?,?,?)",
					id.toString(), version, purpose);
		db.update(
				"INSERT INTO lender_rule_change(product_id,rule_version,actor_id,reason,retry_key,request_hash) VALUES (?,?,?,?,?,?)",
				id.toString(), version, jwt.getSubject(), input.reason().trim(), retry, hash);
		db.update("UPDATE lender_product_head SET current_version=? WHERE product_id=?", version, id.toString());
		return present(db.queryForMap("SELECT * FROM lender_product_rule WHERE product_id=? AND rule_version=?",
				id.toString(), version));
	}

	private static void administrator(Jwt jwt) {
		if (jwt == null || jwt.getClaimAsStringList("roles") == null
				|| !jwt.getClaimAsStringList("roles").contains("ADMIN")
				|| jwt.getClaimAsStringList("roles").contains("LENDER_OFFICER")
				|| jwt.getClaimAsString("client_id") != null)
			throw new ResponseStatusException(FORBIDDEN, "Administrator access required.");
	}

	private Map<String, Object> routingView(Map<String, Object> row) {
		return Map.of("productId", row.get("PRODUCT_ID"), "lenderId", row.get("LENDER_ID"), "productName",
				row.get("PRODUCT_NAME"), "version", ((Number) row.get("RULE_VERSION")).intValue(), "routingPriority",
				((Number) row.get("ROUTING_PRIORITY")).intValue(), "active",
				((Number) row.get("ACTIVE")).intValue() == 1);
	}

	@Transactional(readOnly = true)
	public Object routingPriorities(Jwt jwt) {
		administrator(jwt);
		return Map.of("items", db.queryForList(
				"SELECT R.* FROM lender_product_rule R JOIN lender_product_head H ON H.product_id=R.product_id AND H.current_version=R.rule_version ORDER BY R.routing_priority,R.product_id")
				.stream().map(this::routingView).toList());
	}

	public Object updateRoutingPriority(UUID id, RoutingPriorityInput input, String key, Jwt jwt) {
		administrator(jwt);
		if (input.expectedVersion() == null || input.expectedVersion() < 1 || input.routingPriority() == null
				|| input.routingPriority() < 1 || input.routingPriority() > 99999 || input.reason() == null
				|| input.reason().isBlank() || input.reason().length() > 450)
			throw new ResponseStatusException(BAD_REQUEST, "Enter a valid routing position and a change reason.");
		try {
			UUID.fromString(key);
		} catch (Exception ex) {
			throw new ResponseStatusException(BAD_REQUEST, "A valid retry key is required.");
		}
		String retry = jwt.getSubject() + ":routing:" + key;
		String requestHash;
		try {
			requestHash = HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		db.queryForList("SELECT LOCK_ID FROM CF_ROUTING_LOCK WHERE LOCK_ID=1 FOR UPDATE", Integer.class);
		var head = db.queryForList("SELECT current_version FROM lender_product_head WHERE product_id=? FOR UPDATE",
				Integer.class, id.toString());
		if (head.isEmpty())
			throw new ResponseStatusException(NOT_FOUND, "Lender product not found.");
		var replay = db.queryForList(
				"SELECT PRODUCT_ID,RULE_VERSION,REQUEST_HASH FROM lender_rule_change WHERE retry_key=?", retry);
		if (!replay.isEmpty()) {
			var prior = replay.getFirst();
			if (!id.toString().equals(prior.get("PRODUCT_ID")) || !requestHash.equals(prior.get("REQUEST_HASH")))
				throw new ResponseStatusException(CONFLICT, "Retry key was used for a different priority update.");
			return routingView(db.queryForMap("SELECT * FROM lender_product_rule WHERE product_id=? AND rule_version=?",
					id.toString(), prior.get("RULE_VERSION")));
		}
		int current = head.getFirst();
		if (current != input.expectedVersion())
			throw new ResponseStatusException(CONFLICT, "Rules changed. Refresh before saving.");
		var rows = db.queryForList(
				"SELECT R.* FROM lender_product_rule R JOIN lender_product_head H ON H.product_id=R.product_id AND H.current_version=R.rule_version ORDER BY R.routing_priority,R.product_id");
		if (input.routingPriority() > rows.size())
			throw new ResponseStatusException(BAD_REQUEST, "Priority must be between 1 and " + rows.size() + ".");
		var selected = rows.stream().filter(r -> id.toString().equals(r.get("PRODUCT_ID"))).findFirst().orElseThrow();
		int previous = ((Number) selected.get("ROUTING_PRIORITY")).intValue();
		var positions = rows.stream().map(r -> ((Number) r.get("ROUTING_PRIORITY")).intValue())
				.collect(java.util.stream.Collectors.toSet());
		if (positions.size() != rows.size()
				|| positions.stream().anyMatch(position -> position < 1 || position > rows.size()))
			throw new ResponseStatusException(CONFLICT,
					"Routing positions must be unique and consecutive. Refresh or repair the order before saving.");
		int lower = Math.min(previous, input.routingPriority()), upper = Math.max(previous, input.routingPriority());
		// Preserve relative order by shifting all products in the affected range one
		// position.
		var changed = new ArrayList<>(rows.stream().filter(r -> {
			int position = ((Number) r.get("ROUTING_PRIORITY")).intValue();
			return position >= lower && position <= upper;
		}).toList());
		changed.sort(Comparator.comparing(r -> r.get("PRODUCT_ID").toString()));
		for (var row : changed) {
			String product = row.get("PRODUCT_ID").toString();
			int locked = db
					.queryForList("SELECT current_version FROM lender_product_head WHERE product_id=? FOR UPDATE",
							Integer.class, product)
					.getFirst();
			if (locked != ((Number) row.get("RULE_VERSION")).intValue())
				throw new ResponseStatusException(CONFLICT, "Rules changed. Refresh before saving.");
		}
		for (var row : changed) {
			String product = row.get("PRODUCT_ID").toString();
			boolean selectedProduct = product.equals(id.toString());
			int position = ((Number) row.get("ROUTING_PRIORITY")).intValue();
			int target = selectedProduct ? input.routingPriority()
					: position + (input.routingPriority() > previous ? -1 : 1);
			String note = selectedProduct ? input.reason().trim() : "Automatic reorder: " + input.reason().trim();
			savePriority(product, ((Number) row.get("RULE_VERSION")).intValue(), target, jwt.getSubject(), note,
					selectedProduct ? retry : shiftRetry(retry, product), requestHash);
		}
		return routingView(db.queryForMap("SELECT * FROM lender_product_rule WHERE product_id=? AND rule_version=?",
				id.toString(), current + 1));
	}

	private static String shiftRetry(String retry, String product) {
		try {
			return "routing-shift:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest((retry + ":" + product).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void savePriority(String product, int current, int priority, String actor, String reason, String retry,
			String hash) {
		int version = Math.addExact(current, 1);
		db.update(
				"""
						INSERT INTO lender_product_rule(product_id,rule_version,lender_id,product_name,min_age_months,min_average_net,max_return_percent,min_amount,max_amount,sales_multiplier,routing_priority,active,min_credit_score_band,max_repayment_percent,assessment_apr_percent)
						SELECT product_id,?,lender_id,product_name,min_age_months,min_average_net,max_return_percent,min_amount,max_amount,sales_multiplier,?,active,min_credit_score_band,max_repayment_percent,assessment_apr_percent
						FROM lender_product_rule WHERE product_id=? AND rule_version=?
						""",
				version, priority, product, current);
		db.update(
				"INSERT INTO lender_product_purpose(product_id,rule_version,purpose) SELECT product_id,?,purpose FROM lender_product_purpose WHERE product_id=? AND rule_version=?",
				version, product, current);
		db.update(
				"INSERT INTO lender_rule_change(product_id,rule_version,actor_id,reason,retry_key,request_hash) VALUES (?,?,?,?,?,?)",
				product, version, actor, "[ADMIN_ROUTING] " + reason, retry, hash);
		db.update("UPDATE lender_product_head SET current_version=? WHERE product_id=?", version, product);
	}

	public static void validate(LenderRulesInput i) {
		if (com.credflow.eligibility.EligibilityCalculator.band(i.minCreditScoreBand()) < 0
				|| new BigDecimal(i.maxRepaymentPercent()).signum() <= 0
				|| new BigDecimal(i.maxRepaymentPercent()).compareTo(new BigDecimal("100")) > 0
				|| new BigDecimal(i.assessmentAprPercent()).signum() < 0
				|| new BigDecimal(i.assessmentAprPercent()).compareTo(new BigDecimal("100")) > 0)
			throw new ResponseStatusException(BAD_REQUEST,
					"Check credit band, repayment share and assessment interest rate.");
		var min = new BigDecimal(i.minAmount());
		var max = new BigDecimal(i.maxAmount());
		var returns = new BigDecimal(i.maxReturnPercent());
		if (i.productName().isBlank() || i.reason().isBlank() || min.signum() <= 0 || max.compareTo(min) < 0
				|| returns.signum() < 0 || returns.compareTo(new BigDecimal("100")) > 0
				|| new BigDecimal(i.salesMultiplier()).signum() <= 0)
			throw new ResponseStatusException(BAD_REQUEST,
					"Check the product name, amount range, return percentage, multiplier and change reason.");
	}

	private String hash(LenderRulesInput input) {
		try {
			var data = new TreeMap<String, Object>(json.convertValue(input, Map.class));
			data.put("purposes", new TreeSet<>(input.purposes()));
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
