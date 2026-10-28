package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "IntegrationTask")
@Table(name = "CF_INT_TASK")
public class IntegrationTask {

	@Id
	private UUID taskId;

	@Column(nullable = false)
	private UUID submissionId;

	@Column(nullable = false, length = 30)
	private String operation;

	@Column(nullable = false, length = 64)
	private String payloadHash;

	@Lob
	@Column(nullable = false)
	private String payloadJson;

	@Column(nullable = false, length = 12)
	private String status = "PENDING";

	@Column(nullable = false)
	private Instant nextAttemptAt = Instant.now();

	private int attempts;

	private long position;

	private UUID leaseToken;

	@Column(length = 300)
	private String failureCode;

	@Version
	private Long rowVersion;

	public IntegrationTask() {
	}

	public UUID getTaskId() {
		return taskId;
	}

	public void setTaskId(UUID taskId) {
		this.taskId = taskId;
	}

	public UUID getSubmissionId() {
		return submissionId;
	}

	public void setSubmissionId(UUID submissionId) {
		this.submissionId = submissionId;
	}

	public String getOperation() {
		return operation;
	}

	public void setOperation(String operation) {
		this.operation = operation;
	}

	public String getPayloadHash() {
		return payloadHash;
	}

	public void setPayloadHash(String payloadHash) {
		this.payloadHash = payloadHash;
	}

	public String getPayloadJson() {
		return payloadJson;
	}

	public void setPayloadJson(String payloadJson) {
		this.payloadJson = payloadJson;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public void setNextAttemptAt(Instant nextAttemptAt) {
		this.nextAttemptAt = nextAttemptAt;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public int incrementAttempts() {
		return ++attempts;
	}

	public long getPosition() {
		return position;
	}

	public void setPosition(long position) {
		this.position = position;
	}

	public UUID getLeaseToken() {
		return leaseToken;
	}

	public void setLeaseToken(UUID leaseToken) {
		this.leaseToken = leaseToken;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public void setFailureCode(String failureCode) {
		this.failureCode = failureCode;
	}

	public Long getRowVersion() {
		return rowVersion;
	}

	public void setRowVersion(Long rowVersion) {
		this.rowVersion = rowVersion;
	}
}
