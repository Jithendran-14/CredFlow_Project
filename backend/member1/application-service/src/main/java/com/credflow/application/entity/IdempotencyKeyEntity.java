package com.credflow.application.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "CF_IDEMPOTENCY_KEYS", uniqueConstraints = {
		@UniqueConstraint(name = "UQ_CF_IDEMPOTENCY_KEY", columnNames = { "MARKETPLACE_ID", "IDEMPOTENCY_KEY" }) })
public class IdempotencyKeyEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "IDEMPOTENCY_ID", nullable = false, updatable = false)
	private Long idempotencyId;

	@Column(name = "MARKETPLACE_ID", nullable = false, length = 36)
	private String marketplaceId;

	@Column(name = "IDEMPOTENCY_KEY", nullable = false, length = 255)
	private String idempotencyKey;

	@Column(name = "REQUEST_FINGERPRINT", nullable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "PUBLIC_APPLICATION_ID", nullable = false, length = 36)
	private String publicApplicationId;

	@Column(name = "CREATED_AT", nullable = false)
	private OffsetDateTime createdAt;

	public IdempotencyKeyEntity() {
	}

	@PrePersist
	protected void onCreate() {
		if (createdAt == null) {
			createdAt = OffsetDateTime.now();
		}
	}

	public Long getIdempotencyId() {
		return idempotencyId;
	}

	public String getMarketplaceId() {
		return marketplaceId;
	}

	public void setMarketplaceId(String marketplaceId) {
		this.marketplaceId = marketplaceId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public String getRequestFingerprint() {
		return requestFingerprint;
	}

	public void setRequestFingerprint(String requestFingerprint) {
		this.requestFingerprint = requestFingerprint;
	}

	public String getPublicApplicationId() {
		return publicApplicationId;
	}

	public void setPublicApplicationId(String publicApplicationId) {
		this.publicApplicationId = publicApplicationId;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
