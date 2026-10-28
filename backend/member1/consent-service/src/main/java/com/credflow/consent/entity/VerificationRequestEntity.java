package com.credflow.consent.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_VERIFICATION_REQUESTS")
public class VerificationRequestEntity {

	@Id
	@Column(name = "VERIFICATION_ID", length = 36, nullable = false, updatable = false)
	private String verificationId;

	@Column(name = "APPLICATION_ID", length = 36, nullable = false)
	private String applicationId;

	@Column(name = "APPLICATION_REVISION", nullable = false)
	private Integer applicationRevision;

	@Column(name = "CONSENT_ID", length = 36, nullable = false)
	private String consentId;

	@Column(name = "CONSENT_VERSION", nullable = false)
	private Integer consentVersion;

	@Column(name = "REQUEST_HASH", length = 64, nullable = false)
	private String requestHash;

	@Lob
	@Column(name = "REQUEST_PAYLOAD", nullable = false, columnDefinition = "CLOB")
	private String requestPayload;

	@Column(name = "CORRELATION_ID", length = 100, nullable = false)
	private String correlationId;

	@Column(name = "CAUSATION_ID", length = 36)
	private String causationId;

	@Column(name = "PROCESSING_STATUS", length = 20, nullable = false)
	private String processingStatus = "PENDING";

	@Column(name = "ATTEMPT_COUNT", nullable = false)
	private Integer attemptCount = 0;

	@Column(name = "NEXT_ATTEMPT_AT", nullable = false)
	private OffsetDateTime nextAttemptAt;

	@Column(name = "CLAIM_TOKEN", length = 36)
	private String claimToken;

	@Column(name = "LEASE_EXPIRES_AT")
	private OffsetDateTime leaseExpiresAt;

	@Column(name = "CREATED_AT", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "UPDATED_AT", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "COMPLETED_AT")
	private OffsetDateTime completedAt;

	protected VerificationRequestEntity() {
		// Required by JPA.
	}

	public VerificationRequestEntity(UUID verificationId, UUID applicationId, int applicationRevision, UUID consentId,
			int consentVersion, String requestHash, String requestPayload, String correlationId, UUID causationId) {

		this.verificationId = verificationId.toString();
		this.applicationId = applicationId.toString();
		this.applicationRevision = applicationRevision;
		this.consentId = consentId.toString();
		this.consentVersion = consentVersion;
		this.requestHash = requestHash;
		this.requestPayload = requestPayload;
		this.correlationId = correlationId;
		this.causationId = causationId == null ? null : causationId.toString();
	}

	@PrePersist
	protected void onCreate() {

		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

		updatedAt = now;

		if (nextAttemptAt == null) {
			nextAttemptAt = now;
		}
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
	}

	public String getVerificationId() {
		return verificationId;
	}

	public String getApplicationId() {
		return applicationId;
	}

	public Integer getApplicationRevision() {
		return applicationRevision;
	}

	public String getConsentId() {
		return consentId;
	}

	public Integer getConsentVersion() {
		return consentVersion;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public String getRequestPayload() {
		return requestPayload;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public String getCausationId() {
		return causationId;
	}

	public String getProcessingStatus() {
		return processingStatus;
	}
}