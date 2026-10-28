package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "IntegrationDispatch")
@Table(name = "CF_INT_DISPATCH")
public class IntegrationDispatch {

	@Id
	private UUID submissionId;

	@Column(nullable = false)
	private UUID applicationId;

	@Column(nullable = false, length = 80)
	private String lenderId;

	@Column(nullable = false)
	private int applicationRevision;

	@Column(nullable = false, length = 64)
	private String payloadHash;

	@Lob
	@Column(nullable = false)
	private String requestJson;

	@Column(length = 120)
	private String externalId;

	@Column(nullable = false, length = 30)
	private String status = "QUEUED";

	@Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
	@org.hibernate.annotations.JdbcTypeCode(java.sql.Types.NUMERIC)
	private boolean cancellationRequested;

	@Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
	@org.hibernate.annotations.JdbcTypeCode(java.sql.Types.NUMERIC)
	private boolean cancellationAcknowledged;

	private long callbackSequence;

	private long eventVersion;

	private Instant submittedAt;

	private UUID leaseToken;

	private Instant leaseUntil;

	@Version
	private Long rowVersion;

	public IntegrationDispatch() {
	}

	public UUID getSubmissionId() {
		return submissionId;
	}

	public void setSubmissionId(UUID submissionId) {
		this.submissionId = submissionId;
	}

	public UUID getApplicationId() {
		return applicationId;
	}

	public void setApplicationId(UUID applicationId) {
		this.applicationId = applicationId;
	}

	public String getLenderId() {
		return lenderId;
	}

	public void setLenderId(String lenderId) {
		this.lenderId = lenderId;
	}

	public int getApplicationRevision() {
		return applicationRevision;
	}

	public void setApplicationRevision(int applicationRevision) {
		this.applicationRevision = applicationRevision;
	}

	public String getPayloadHash() {
		return payloadHash;
	}

	public void setPayloadHash(String payloadHash) {
		this.payloadHash = payloadHash;
	}

	public String getRequestJson() {
		return requestJson;
	}

	public void setRequestJson(String requestJson) {
		this.requestJson = requestJson;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public boolean isCancellationRequested() {
		return cancellationRequested;
	}

	public void setCancellationRequested(boolean cancellationRequested) {
		this.cancellationRequested = cancellationRequested;
	}

	public boolean isCancellationAcknowledged() {
		return cancellationAcknowledged;
	}

	public void setCancellationAcknowledged(boolean cancellationAcknowledged) {
		this.cancellationAcknowledged = cancellationAcknowledged;
	}

	public long getCallbackSequence() {
		return callbackSequence;
	}

	public void setCallbackSequence(long callbackSequence) {
		this.callbackSequence = callbackSequence;
	}

	public long getEventVersion() {
		return eventVersion;
	}

	public void setEventVersion(long eventVersion) {
		this.eventVersion = eventVersion;
	}

	public long incrementEventVersion() {
		return ++eventVersion;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}

	public void setSubmittedAt(Instant submittedAt) {
		this.submittedAt = submittedAt;
	}

	public UUID getLeaseToken() {
		return leaseToken;
	}

	public void setLeaseToken(UUID leaseToken) {
		this.leaseToken = leaseToken;
	}

	public Instant getLeaseUntil() {
		return leaseUntil;
	}

	public void setLeaseUntil(Instant leaseUntil) {
		this.leaseUntil = leaseUntil;
	}

	public Long getRowVersion() {
		return rowVersion;
	}

	public void setRowVersion(Long rowVersion) {
		this.rowVersion = rowVersion;
	}
}
