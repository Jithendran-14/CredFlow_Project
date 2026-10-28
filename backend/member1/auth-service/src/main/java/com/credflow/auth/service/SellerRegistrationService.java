package com.credflow.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.credflow.auth.dto.SellerRegistrationRequest;
import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.repository.AuthUserRepository;

@Service
public class SellerRegistrationService {
	private final AuthUserRepository users;
	private final PasswordEncoder encoder;
	private final JdbcTemplate jdbc;
	private final SellerReferenceService references;

	public SellerRegistrationService(AuthUserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc,
			SellerReferenceService references) {
		this.users = users;
		this.encoder = encoder;
		this.jdbc = jdbc;
		this.references = references;
	}

	public List<Map<String, Object>> marketplaces() {
		return jdbc.queryForList(
				"SELECT U.MARKETPLACE_ID AS \"marketplaceId\", COALESCE(MIN(O.ORGANIZATION_NAME),MIN(U.USERNAME)) AS \"name\" FROM CF_AUTH_USERS U LEFT JOIN CF_ORGANIZATIONS O ON O.ORGANIZATION_ID=U.MARKETPLACE_ID AND O.ORGANIZATION_TYPE='MARKETPLACE' WHERE U.USER_ROLE='MARKETPLACE' AND U.ACCOUNT_STATUS='ACTIVE' AND O.ORGANIZATION_STATUS='ACTIVE' AND U.MARKETPLACE_ID IS NOT NULL AND U.USER_ID NOT IN(SELECT USER_ID FROM CF_AUTH_SERVICE_CLIENTS) GROUP BY U.MARKETPLACE_ID ORDER BY MIN(U.USERNAME)");
	}

	public List<Map<String, Object>> lenders() {
		return jdbc.queryForList(
				"SELECT U.LENDER_ID AS \"lenderId\",COALESCE(MIN(O.ORGANIZATION_NAME),MIN(U.USERNAME)) AS \"name\" FROM CF_AUTH_USERS U LEFT JOIN CF_ORGANIZATIONS O ON O.ORGANIZATION_ID=U.LENDER_ID AND O.ORGANIZATION_TYPE='LENDER' WHERE U.USER_ROLE='LENDER_OFFICER' AND U.ACCOUNT_STATUS='ACTIVE' AND U.LENDER_ID IS NOT NULL GROUP BY U.LENDER_ID ORDER BY MIN(U.USERNAME)");
	}

	@Transactional
	public Map<String, String> register(SellerRegistrationRequest r) {
		if (r.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must contain at most 72 UTF-8 bytes.");
		String username = r.username().trim().toLowerCase(Locale.ROOT),
				email = r.emailAddress().trim().toLowerCase(Locale.ROOT);
		if (users.existsByUsernameIgnoreCase(username) || users.existsByEmailAddressIgnoreCase(email))
			throw new IllegalArgumentException("Username or email is already registered.");
		var organization = jdbc.queryForList(
				"SELECT ORGANIZATION_STATUS FROM CF_ORGANIZATIONS WHERE ORGANIZATION_ID=? AND ORGANIZATION_TYPE='MARKETPLACE' FOR UPDATE",
				r.marketplaceId());
		if (organization.isEmpty() || !"ACTIVE".equals(organization.getFirst().get("ORGANIZATION_STATUS")))
			throw new IllegalArgumentException("Choose an active marketplace.");
		if (jdbc.queryForObject(
				"SELECT COUNT(*) FROM CF_AUTH_USERS WHERE USER_ROLE='MARKETPLACE' AND ACCOUNT_STATUS='ACTIVE' AND MARKETPLACE_ID=?",
				Integer.class, r.marketplaceId()) == 0)
			throw new IllegalArgumentException("Choose an available marketplace.");
		String sellerId = UUID.randomUUID().toString(), reference = references.next(r.marketplaceId());
		AuthUser user = new AuthUser();
		user.setUsername(username);
		user.setEmailAddress(email);
		user.setPasswordHash(encoder.encode(r.password()));
		user.setUserRole("SELLER");
		user.setSellerId(sellerId);
		user.setMarketplaceId(r.marketplaceId());
		user.setAccountStatus("ACTIVE");
		users.saveAndFlush(user);
		jdbc.update(
				"INSERT INTO CF_SELLER_PROFILES (SELLER_ID,USER_ID,MARKETPLACE_ID,MARKETPLACE_SELLER_ID,FULL_NAME,PHONE,BUSINESS_NAME,BUSINESS_START_DATE,ADDRESS,CITY,STATE,POSTAL_CODE) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
				sellerId, user.getUserId(), r.marketplaceId(), reference, r.fullName().trim(), r.phone(),
				r.businessName().trim(), java.sql.Date.valueOf(r.businessStartDate()), r.address().trim(),
				r.city().trim(), r.state().trim(), r.postalCode());
		// A restricted INSERT grant keeps account, profile, and trusted marketplace
		// mapping
		// in the same Oracle transaction. No second database connection or partial
		// registration.
		jdbc.update(
				"INSERT INTO CF_APPLICATION.CF_SELLER_MAPPINGS (MARKETPLACE_ID,MARKETPLACE_SELLER_ID,SELLER_ID) VALUES (?,?,?)",
				r.marketplaceId(), reference, sellerId);
		return Map.of("userId", user.getPublicUserId(), "sellerId", sellerId, "marketplaceSellerId", reference,
				"message", "Seller registered. Sign in with your chosen username and password.");
	}

	private static final String FIELDS = "SELECT P.ROW_VERSION AS \"version\", P.SELLER_ID AS \"sellerId\", P.MARKETPLACE_ID AS \"marketplaceId\", P.MARKETPLACE_SELLER_ID AS \"marketplaceSellerId\", P.FULL_NAME AS \"fullName\", U.EMAIL_ADDRESS AS \"emailAddress\", P.PHONE AS \"phone\", P.BUSINESS_NAME AS \"businessName\", TO_CHAR(P.BUSINESS_START_DATE,'YYYY-MM-DD') AS \"businessStartDate\", P.ADDRESS AS \"address\", P.CITY AS \"city\", P.STATE AS \"state\", P.POSTAL_CODE AS \"postalCode\" FROM CF_SELLER_PROFILES P JOIN CF_AUTH_USERS U ON U.USER_ID=P.USER_ID";

	public Map<String, Object> profile(Jwt jwt) {
		if (jwt.getClaimAsStringList("roles") == null || !jwt.getClaimAsStringList("roles").contains("SELLER")
				|| jwt.getClaimAsString("sellerId") == null)
			throw new AccessDeniedException("Seller access required.");
		var rows = jdbc.queryForList(FIELDS + " WHERE P.SELLER_ID=?", jwt.getClaimAsString("sellerId"));
		return rows.isEmpty() ? Map.of() : rows.getFirst();
	}

	public Map<String, Object> directory(Jwt jwt, int page, int size) {
		if (page < 0 || size < 1 || size > 100)
			throw new IllegalArgumentException("Invalid pagination.");
		if (jwt.getClaimAsString("marketplaceId") == null)
			throw new AccessDeniedException("Marketplace identity required.");
		String id = jwt.getClaimAsString("marketplaceId");
		return Map.of("items",
				jdbc.queryForList(FIELDS
						+ " WHERE P.MARKETPLACE_ID=? ORDER BY P.CREATED_AT DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
						id, (long) page * size, size),
				"totalElements",
				jdbc.queryForObject("SELECT COUNT(*) FROM CF_SELLER_PROFILES WHERE MARKETPLACE_ID=?", Long.class, id));
	}
}
