package com.credflow.consent.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerIdentityMappingService {

	private final JdbcTemplate jdbc;

	public SellerIdentityMappingService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record IdentityMapping(String sellerId, UUID marketplaceId, String fixtureIdentityId) {
	}

	@Transactional(readOnly = true)
	public Optional<IdentityMapping> findActive(String sellerId, UUID marketplaceId) {

		if (sellerId == null || sellerId.isBlank() || marketplaceId == null) {

			throw new IllegalArgumentException("Seller and marketplace identities are required");
		}

		var mappings = jdbc.query("""
				SELECT
				    SELLER_ID,
				    MARKETPLACE_ID,
				    FIXTURE_IDENTITY_ID
				FROM CF_SELLER_IDENTITY_MAP
				WHERE SELLER_ID = ?
				  AND MARKETPLACE_ID = ?
				  AND MAPPING_STATUS = 'ACTIVE'
				""",
				(rs, rowNumber) -> new IdentityMapping(rs.getString("SELLER_ID"),
						UUID.fromString(rs.getString("MARKETPLACE_ID")), rs.getString("FIXTURE_IDENTITY_ID")),
				sellerId, marketplaceId.toString());

		return mappings.stream().findFirst();
	}
}