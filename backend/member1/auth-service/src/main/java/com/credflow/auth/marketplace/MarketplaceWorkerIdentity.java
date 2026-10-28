package com.credflow.auth.marketplace;

import com.credflow.auth.repository.*;
import com.credflow.auth.service.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal identity issuance for the Auth-owned queue worker; never exposed as
 * an HTTP endpoint.
 */
@Service
public class MarketplaceWorkerIdentity {
	private final AuthUserRepository users;
	private final AuthServiceClientRepository clients;
	private final JwtService jwt;

	public MarketplaceWorkerIdentity(AuthUserRepository users, AuthServiceClientRepository clients, JwtService jwt) {
		this.users = users;
		this.clients = clients;
		this.jwt = jwt;
	}

	@Transactional(readOnly = true)
	public String token(String market) {
		String id = "marketplace-submission-" + market;
		var client = clients.findByClientId(id)
				.orElseThrow(() -> new IllegalStateException("Marketplace service registration missing"));
		var user = users.findById(client.getUserId()).orElseThrow();
		if (!"MARKETPLACE".equals(user.getUserRole()) || !"ACTIVE".equals(user.getAccountStatus())
				|| !market.equals(user.getMarketplaceId()) || user.getSellerId() != null || user.getLenderId() != null
				|| !"application.submit".equals(client.getScopes()))
			throw new IllegalStateException("Marketplace service binding is invalid");
		return jwt.generateClientCredentialsToken(user, id, "application.submit");
	}
}
