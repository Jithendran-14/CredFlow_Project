package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "CF_SIM_CALLBACK", uniqueConstraints = @UniqueConstraint(name = "UK_SIM_CALLBACK_SEQUENCE", columnNames = {
		"CASE_KEY", "CALLBACK_SEQUENCE" }))
public class SimCallback {

	@Id
	@Column(name = "EVENT_ID")
	private UUID id;

	@Column(name = "CASE_KEY", nullable = false, length = 120)
	private String caseKey;

	@Column(name = "LENDER_CODE", nullable = false, length = 20)
	private String lenderCode;

	@Column(name = "LENDER_ID", nullable = false, length = 80)
	private String lenderId;

	@Column(name = "CALLBACK_SEQUENCE", nullable = false)
	private long sequence;

	@Lob
	@Column(name = "PAYLOAD_JSON", nullable = false)
	private String payload;

	@Column(name = "STATUS", nullable = false, length = 16)
	private String status;

	@Column(name = "ATTEMPTS", nullable = false)
	private int attempts;

	@Column(name = "NEXT_ATTEMPT_AT", nullable = false)
	private Instant nextAttempt;

	@Column(name = "LEASE_ID")
	private UUID leaseId;

	@Column(name = "FAILURE_CODE", length = 80)
	private String failure;

	@Version
	@Column(name = "ROW_VERSION")
	private Long version;

	public SimCallback() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getCaseKey() {
		return caseKey;
	}

	public void setCaseKey(String caseKey) {
		this.caseKey = caseKey;
	}

	public String getLenderCode() {
		return lenderCode;
	}

	public void setLenderCode(String lenderCode) {
		this.lenderCode = lenderCode;
	}

	public String getLenderId() {
		return lenderId;
	}

	public void setLenderId(String lenderId) {
		this.lenderId = lenderId;
	}

	public long getSequence() {
		return sequence;
	}

	public void setSequence(long sequence) {
		this.sequence = sequence;
	}

	public long incrementSequence() {
		return ++sequence;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
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

	public Instant getNextAttempt() {
		return nextAttempt;
	}

	public void setNextAttempt(Instant nextAttempt) {
		this.nextAttempt = nextAttempt;
	}

	public UUID getLeaseId() {
		return leaseId;
	}

	public void setLeaseId(UUID leaseId) {
		this.leaseId = leaseId;
	}

	public String getFailure() {
		return failure;
	}

	public void setFailure(String failure) {
		this.failure = failure;
	}

	public Long getVersion() {
		return version;
	}

	public void setVersion(Long version) {
		this.version = version;
	}

	public Long incrementVersion() {
		return ++version;
	}
}
