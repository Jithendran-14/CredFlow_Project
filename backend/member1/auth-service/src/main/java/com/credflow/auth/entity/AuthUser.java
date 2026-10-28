
package com.credflow.auth.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;

@Entity
@Table(name = "CF_AUTH_USERS")
public class AuthUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "USER_ID")
	private Long userId;

	@Column(name = "PUBLIC_USER_ID", nullable = false, unique = true, length = 36)
	private String publicUserId;

	@Column(name = "USERNAME", nullable = false, unique = true, length = 100)
	private String username;

	@Column(name = "EMAIL_ADDRESS", nullable = false, unique = true, length = 255)
	private String emailAddress;

	@Column(name = "PASSWORD_HASH", nullable = false, length = 255)
	private String passwordHash;

	@Column(name = "USER_ROLE", nullable = false, length = 30)
	private String userRole;

	@Column(name = "SELLER_ID", length = 100)
	private String sellerId;

	@Column(name = "MARKETPLACE_ID", length = 100)
	private String marketplaceId;

	@Column(name = "LENDER_ID", length = 100)
	private String lenderId;

	@Column(name = "ACCOUNT_STATUS", nullable = false, length = 20)
	private String accountStatus = "ACTIVE";

	@Column(name = "CREATED_AT", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "UPDATED_AT", nullable = false)
	private OffsetDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		OffsetDateTime now = OffsetDateTime.now();

		if (publicUserId == null) {
			publicUserId = UUID.randomUUID().toString();
		}

		if (accountStatus == null) {
			accountStatus = "ACTIVE";
		}

		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = OffsetDateTime.now();
	}

	public Long getUserId() {
		return userId;
	}

	public String getPublicUserId() {
		return publicUserId;
	}

	public String getUsername() {
		return username;
	}

	public String getEmailAddress() {
		return emailAddress;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getUserRole() {
		return userRole;
	}

	public String getSellerId() {
		return sellerId;
	}

	public String getMarketplaceId() {
		return marketplaceId;
	}

	public String getLenderId() {
		return lenderId;
	}

	public void setLenderId(String value) {
		lenderId = value;
	}

	public String getAccountStatus() {
		return accountStatus;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public void setEmailAddress(String emailAddress) {
		this.emailAddress = emailAddress;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public void setUserRole(String userRole) {
		this.userRole = userRole;
	}

	public void setSellerId(String sellerId) {
		this.sellerId = sellerId;
	}

	public void setMarketplaceId(String marketplaceId) {
		this.marketplaceId = marketplaceId;
	}

	public void setAccountStatus(String accountStatus) {
		this.accountStatus = accountStatus;
	}
}
