package com.credflow.auth.service;

import java.util.Locale;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.auth.dto.CreateUserRequest;
import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.repository.AuthUserRepository;

@Service
public class AdminUserService {

	private static final Map<String, Boolean> ALLOWED_ROLES = Map.of("SELLER", true, "MARKETPLACE", true, "ADMIN", true,
			"ELIGIBILITY_SERVICE", true, "VERIFICATION_SERVICE", true, "ORCHESTRATION_SERVICE", true,
			"INTEGRATION_SERVICE", true, "LENDER_SERVICE", true, "LENDER_OFFICER", true);
	private final AuthUserRepository repository;
	private final PasswordEncoder passwordEncoder;

	public AdminUserService(AuthUserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public String createUser(CreateUserRequest request) {
		String role = request.role().trim().toUpperCase(Locale.ROOT);

		if (!ALLOWED_ROLES.containsKey(role)) {
			throw new IllegalArgumentException("Unsupported user role.");
		}

		if (repository.existsByUsernameIgnoreCase(request.username().trim())) {
			throw new IllegalArgumentException("Username is already registered.");
		}

		if (repository.existsByEmailAddressIgnoreCase(request.emailAddress().trim())) {
			throw new IllegalArgumentException("Email address is already registered.");
		}

		String sellerId = blankToNull(request.sellerId());
		String marketplaceId = blankToNull(request.marketplaceId());
		String lenderId = blankToNull(request.lenderId());
		if ("LENDER_OFFICER".equals(role) && (lenderId == null || sellerId != null || marketplaceId != null))
			throw new IllegalArgumentException("Lender officer requires only a verified lender ID.");
		if (!"LENDER_OFFICER".equals(role) && lenderId != null)
			throw new IllegalArgumentException("Only a lender officer may have a lender ID.");

		boolean serviceAccount = "ELIGIBILITY_SERVICE".equals(role) || "VERIFICATION_SERVICE".equals(role)
				|| "ORCHESTRATION_SERVICE".equals(role) || "INTEGRATION_SERVICE".equals(role)
				|| "LENDER_SERVICE".equals(role);

		if (serviceAccount && (sellerId != null || marketplaceId != null)) {
			throw new IllegalArgumentException("Service accounts must not have seller or marketplace IDs.");
		}

		if ("SELLER".equals(role) && sellerId == null) {
			throw new IllegalArgumentException("A seller account requires a verified seller ID.");
		}

		if ("MARKETPLACE".equals(role) && marketplaceId == null) {
			throw new IllegalArgumentException("A marketplace account requires a verified marketplace ID.");
		}

		AuthUser user = new AuthUser();
		user.setUsername(request.username().trim());
		user.setEmailAddress(request.emailAddress().trim().toLowerCase(Locale.ROOT));
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setUserRole(role);
		user.setSellerId(sellerId);
		user.setMarketplaceId(marketplaceId);
		user.setLenderId(lenderId);
		user.setAccountStatus("ACTIVE");

		AuthUser saved = repository.save(user);
		return saved.getPublicUserId();
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
