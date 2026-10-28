package com.credflow.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_AUTH_SERVICE_CLIENTS")
public class AuthServiceClient {

	@Id
	@Column(name = "USER_ID", nullable = false)
	private Long userId;

	@Column(name = "CLIENT_ID", nullable = false, unique = true, length = 100)
	private String clientId;

	@Column(name = "SCOPES", nullable = false, length = 1000)
	private String scopes;

	protected AuthServiceClient() {
		// Required by JPA.
	}

	public Long getUserId() {
		return userId;
	}

	public String getClientId() {
		return clientId;
	}

	public String getScopes() {
		return scopes;
	}
}