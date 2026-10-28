package com.credflow.auth.management;

import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.repository.AuthUserRepository;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class LenderManagement {
	private final JdbcTemplate db;
	private final AuthUserRepository users;
	private final PasswordEncoder encoder;

	public LenderManagement(JdbcTemplate db, AuthUserRepository users, PasswordEncoder encoder) {
		this.db = db;
		this.users = users;
		this.encoder = encoder;
	}

	private static final String FIELDS = "SELECT O.ORGANIZATION_ID AS \"lenderId\",O.ORGANIZATION_NAME AS \"name\",O.ORGANIZATION_STATUS AS \"status\",R.LENDER_CODE AS \"code\",R.LENDER_TYPE AS \"type\",(SELECT MIN(USERNAME) FROM CF_AUTH_USERS U WHERE U.LENDER_ID=O.ORGANIZATION_ID AND U.USER_ROLE='LENDER_OFFICER') AS \"username\",(SELECT MIN(EMAIL_ADDRESS) FROM CF_AUTH_USERS U WHERE U.LENDER_ID=O.ORGANIZATION_ID AND U.USER_ROLE='LENDER_OFFICER') AS \"emailAddress\" FROM CF_ORGANIZATIONS O JOIN CF_LENDER_REGISTRY R ON R.LENDER_ID=O.ORGANIZATION_ID WHERE O.ORGANIZATION_TYPE='LENDER'";

	@Transactional(readOnly = true)
	public Object list(Jwt jwt) {
		MarketplaceManagement.admin(jwt);
		return Map.of("items", db.queryForList(FIELDS + " ORDER BY O.ORGANIZATION_NAME"));
	}

	private Object read(String id) {
		return db.queryForMap(FIELDS + " AND O.ORGANIZATION_ID=?", id);
	}

	public Object create(LenderInput i, String key, Jwt jwt) {
		MarketplaceManagement.admin(jwt);
		try {
			UUID.fromString(key);
		} catch (Exception ex) {
			throw new IllegalArgumentException("A valid retry key is required.");
		}
		if (i.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must contain at most 72 UTF-8 bytes.");
		String name = i.name().trim(), code = i.code().trim(), username = i.username().trim().toLowerCase(Locale.ROOT),
				email = i.emailAddress().trim().toLowerCase(Locale.ROOT);
		if (name.isBlank() || i.reason().isBlank())
			throw new IllegalArgumentException("Enter a lender name and creation reason.");
		String retry = jwt.getSubject() + ":lender:" + key, hash;
		try {
			hash = HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256")
							.digest(String.join("\n", name, code, i.type(), username, email, i.reason().trim())
									.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		// Shared with admin reorder operations, so new lenders receive a unique last
		// position.
		db.queryForList("SELECT LOCK_ID FROM CF_ELIGIBILITY.CF_ROUTING_LOCK WHERE LOCK_ID=1 FOR UPDATE");
		var prior = db.queryForList("SELECT ORGANIZATION_ID,CREATE_HASH FROM CF_ORGANIZATIONS WHERE CREATE_KEY=?",
				retry);
		if (!prior.isEmpty()) {
			if (!hash.equals(prior.getFirst().get("CREATE_HASH")) || !users.findByUsernameIgnoreCase(username)
					.map(u -> encoder.matches(i.password(), u.getPasswordHash())).orElse(false))
				throw new ResponseStatusException(CONFLICT, "Retry key was used for different lender details.");
			return read(prior.getFirst().get("ORGANIZATION_ID").toString());
		}
		if (users.existsByUsernameIgnoreCase(username) || users.existsByEmailAddressIgnoreCase(email))
			throw new ResponseStatusException(CONFLICT, "Username or email is already registered.");
		if (db.queryForObject("SELECT COUNT(*) FROM CF_LENDER_REGISTRY WHERE LENDER_CODE=?", Integer.class, code) > 0)
			throw new ResponseStatusException(CONFLICT, "Lender code is already registered.");
		String id = UUID.randomUUID().toString(), product = UUID.randomUUID().toString();
		db.update(
				"INSERT INTO CF_ORGANIZATIONS(ORGANIZATION_ID,ORGANIZATION_NAME,ORGANIZATION_TYPE,CREATE_KEY,CREATE_HASH) VALUES (?,?,'LENDER',?,?)",
				id, name, retry, hash);
		db.update(
				"INSERT INTO CF_LENDER_REGISTRY(LENDER_ID,LENDER_CODE,LENDER_TYPE,ADAPTER,CREATED_BY,CREATION_REASON) VALUES (?,?,?,'BANK_A',?,?)",
				id, code, i.type(), jwt.getSubject(), i.reason().trim());
		var officer = new AuthUser();
		officer.setUsername(username);
		officer.setEmailAddress(email);
		officer.setPasswordHash(encoder.encode(i.password()));
		officer.setUserRole("LENDER_OFFICER");
		officer.setLenderId(id);
		officer.setAccountStatus("ACTIVE");
		users.saveAndFlush(officer);
		int priority = db.queryForObject(
				"SELECT NVL(MAX(R.ROUTING_PRIORITY),0)+1 FROM CF_ELIGIBILITY.LENDER_PRODUCT_RULE R JOIN CF_ELIGIBILITY.LENDER_PRODUCT_HEAD H ON H.PRODUCT_ID=R.PRODUCT_ID AND H.CURRENT_VERSION=R.RULE_VERSION",
				Integer.class);
		// Conservative inactive template. The lender must review and activate its own
		// rules.
		db.update(
				"INSERT INTO CF_ELIGIBILITY.LENDER_PRODUCT_RULE(PRODUCT_ID,RULE_VERSION,LENDER_ID,PRODUCT_NAME,MIN_AGE_MONTHS,MIN_AVERAGE_NET,MAX_RETURN_PERCENT,MIN_AMOUNT,MAX_AMOUNT,SALES_MULTIPLIER,ROUTING_PRIORITY,ACTIVE,MIN_CREDIT_SCORE_BAND,MAX_REPAYMENT_PERCENT,ASSESSMENT_APR_PERCENT) VALUES (?,1,?,?,12,100000,10,10000,1000000,2,?,0,'GOOD',40,18)",
				product, id, name.substring(0, Math.min(name.length(), 86)) + " Business Loan", priority);
		db.update("INSERT INTO CF_ELIGIBILITY.LENDER_PRODUCT_HEAD(PRODUCT_ID,CURRENT_VERSION) VALUES (?,1)", product);
		db.update(
				"INSERT INTO CF_ELIGIBILITY.LENDER_PRODUCT_PURPOSE(PRODUCT_ID,RULE_VERSION,PURPOSE) VALUES (?,1,'WORKING_CAPITAL')",
				product);
		db.update(
				"INSERT INTO CF_ELIGIBILITY.LENDER_RULE_CHANGE(PRODUCT_ID,RULE_VERSION,ACTOR_ID,REASON,RETRY_KEY,REQUEST_HASH) VALUES (?,1,?,?,?,?)",
				product, jwt.getSubject(), "[LENDER_CREATED] " + i.reason().trim(), retry, hash);
		return read(id);
	}
}
