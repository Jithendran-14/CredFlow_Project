package com.credflow.auth.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class PasswordRecoveryService {
	public static final String RECEIPT = "Reset instructions have been sent to your registered email.";
	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwords;
	private final RecoveryMail mail;
	private final RecoveryLimits limits;
	private final boolean enabled;
	private final SecureRandom random = new SecureRandom();

	public PasswordRecoveryService(JdbcTemplate jdbc, PasswordEncoder passwords, RecoveryMail mail,
			RecoveryLimits limits, @Value("${credflow.recovery.enabled:false}") boolean enabled) {
		this.jdbc = jdbc;
		this.passwords = passwords;
		this.mail = mail;
		this.limits = limits;
		this.enabled = enabled;
	}

	private void enabled() {
		if (!enabled)
			throw new ResponseStatusException(SERVICE_UNAVAILABLE,
					"Password recovery is not enabled in this environment.");
	}

	private void throttle(String action, String ip, String username) {
		if (!limits.allow(action, ip, username))
			throw new ResponseStatusException(TOO_MANY_REQUESTS,
					"Too many reset attempts. Wait 15 minutes before trying again.");
	}

	@Transactional(rollbackFor = Exception.class)
	public void request(String username, String ip) throws IOException {
		enabled();
		String normalized = username.trim().toLowerCase(Locale.ROOT);
		throttle("request", ip, normalized);
		var rows = jdbc.queryForList(
				"SELECT USER_ID,EMAIL_ADDRESS,USER_ROLE,LENDER_ID,ACCOUNT_STATUS,PROFILE_VERSION FROM CF_AUTH_USERS WHERE LOWER(USERNAME)=? FOR UPDATE",
				normalized);
		if (rows.isEmpty())
			throw new ResponseStatusException(NOT_FOUND, "User does not exist. Check your username and try again.");
		var user = rows.getFirst();
		String role = Objects.toString(user.get("USER_ROLE"), "");
		if (!"ACTIVE".equals(user.get("ACCOUNT_STATUS")))
			throw new ResponseStatusException(FORBIDDEN, "This account is inactive. Contact your administrator.");
		int port = portal(role, Objects.toString(user.get("LENDER_ID"), ""));
		if (port == 0)
			throw new ResponseStatusException(FORBIDDEN,
					"Password recovery is not available for this account. Contact your administrator.");
		mail.available();
		byte[] entropy = new byte[32];
		random.nextBytes(entropy);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
		jdbc.update("UPDATE CF_PASSWORD_RESET_TOKENS SET USED_AT=SYSTIMESTAMP WHERE USER_ID=? AND USED_AT IS NULL",
				user.get("USER_ID"));
		jdbc.update(
				"INSERT INTO CF_PASSWORD_RESET_TOKENS(TOKEN_HASH,USER_ID,EMAIL_ADDRESS,ACCOUNT_VERSION,EXPIRES_AT) VALUES (?,?,?,?,SYSTIMESTAMP+INTERVAL '15' MINUTE)",
				hash(token), user.get("USER_ID"), user.get("EMAIL_ADDRESS"), user.get("PROFILE_VERSION"));
		// Tokens are never returned in an API response or written to application logs.
		mail.send((String) user.get("EMAIL_ADDRESS"), "http://localhost:" + port + "/#/reset-password/" + token);
	}

	@Transactional
	public void reset(String token, String password, String confirmation, String ip) {
		enabled();
		throttle("reset", ip, null);
		if (!password.equals(confirmation))
			throw new ResponseStatusException(BAD_REQUEST, "The passwords do not match.");
		if (password.getBytes(StandardCharsets.UTF_8).length > 72)
			throw new ResponseStatusException(BAD_REQUEST, "Use a password of at most 72 UTF-8 bytes.");
		String digest = hash(token);
		var tokens = jdbc.queryForList("SELECT USER_ID FROM CF_PASSWORD_RESET_TOKENS WHERE TOKEN_HASH=?", digest);
		if (tokens.isEmpty())
			throw invalid();
		Object id = tokens.getFirst().get("USER_ID");
		// Always lock the account before its tokens, matching the request transaction
		// order.
		var users = jdbc.queryForList(
				"SELECT USER_ID,PASSWORD_HASH,EMAIL_ADDRESS,PROFILE_VERSION,ACCOUNT_STATUS,USER_ROLE,LENDER_ID FROM CF_AUTH_USERS WHERE USER_ID=? FOR UPDATE",
				id);
		if (users.isEmpty())
			throw invalid();
		var user = users.getFirst();
		if (!"ACTIVE".equals(user.get("ACCOUNT_STATUS")) || portal(Objects.toString(user.get("USER_ROLE"), ""),
				Objects.toString(user.get("LENDER_ID"), "")) == 0)
			throw invalid();
		var valid = jdbc.queryForList(
				"SELECT TOKEN_HASH FROM CF_PASSWORD_RESET_TOKENS WHERE TOKEN_HASH=? AND USER_ID=? AND USED_AT IS NULL AND EXPIRES_AT>SYSTIMESTAMP AND ACCOUNT_VERSION=? AND EMAIL_ADDRESS=?",
				digest, id, user.get("PROFILE_VERSION"), user.get("EMAIL_ADDRESS"));
		if (valid.isEmpty())
			throw invalid();
		if (passwords.matches(password, (String) user.get("PASSWORD_HASH")))
			throw new ResponseStatusException(BAD_REQUEST, "Choose a password different from your current password.");
		jdbc.update(
				"UPDATE CF_AUTH_USERS SET PASSWORD_HASH=?,PROFILE_VERSION=PROFILE_VERSION+1,UPDATED_AT=SYSTIMESTAMP WHERE USER_ID=?",
				passwords.encode(password), id);
		jdbc.update("UPDATE CF_PASSWORD_RESET_TOKENS SET USED_AT=SYSTIMESTAMP WHERE USER_ID=? AND USED_AT IS NULL", id);
	}

	static ResponseStatusException invalid() {
		return new ResponseStatusException(BAD_REQUEST,
				"This reset link is invalid, expired or already used. Request a new link.");
	}

	static int portal(String role, String lender) {
		return switch (role) {
		case "SELLER" -> 5175;
		case "MARKETPLACE" -> 5174;
		case "ADMIN" -> 5178;
		case "LENDER_OFFICER" -> {
			try {
				UUID.fromString(lender);
				yield 5176;
			} catch (Exception ex) {
				yield 0;
			}
		}
		default -> 0;
		};
	}

	static String hash(String input) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Required digest unavailable.");
		}
	}
}
