package com.credflow.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "CfOffer")
@Table(name = "CF_OFFER")
public class OfferRow {

	public OfferRow() {
	}

	@Id
	private UUID id;

	private UUID applicationId;

	private UUID submissionId;

	private UUID acceptanceId;

	@Column(precision = 19, scale = 2, nullable = false)
	private BigDecimal amount;

	@Column(precision = 19, scale = 2, nullable = false)
	private BigDecimal fees;

	@Column(precision = 9, scale = 4, nullable = false)
	private BigDecimal rate;

	private String currency;

	private String interestType;

	private String frequency;

	private String termsReference;

	private String status;

	private String acknowledgement;

	private int tenure;

	private long version;

	private Instant expiresAt;

	private Instant acceptedAt;

	@Version
	private Long rowVersion;

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getApplicationId() {
		return applicationId;
	}

	public void setApplicationId(UUID applicationId) {
		this.applicationId = applicationId;
	}

	public UUID getSubmissionId() {
		return submissionId;
	}

	public void setSubmissionId(UUID submissionId) {
		this.submissionId = submissionId;
	}

	public UUID getAcceptanceId() {
		return acceptanceId;
	}

	public void setAcceptanceId(UUID acceptanceId) {
		this.acceptanceId = acceptanceId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public BigDecimal getFees() {
		return fees;
	}

	public void setFees(BigDecimal fees) {
		this.fees = fees;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public void setRate(BigDecimal rate) {
		this.rate = rate;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public String getInterestType() {
		return interestType;
	}

	public void setInterestType(String interestType) {
		this.interestType = interestType;
	}

	public String getFrequency() {
		return frequency;
	}

	public void setFrequency(String frequency) {
		this.frequency = frequency;
	}

	public String getTermsReference() {
		return termsReference;
	}

	public void setTermsReference(String termsReference) {
		this.termsReference = termsReference;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getAcknowledgement() {
		return acknowledgement;
	}

	public void setAcknowledgement(String acknowledgement) {
		this.acknowledgement = acknowledgement;
	}

	public int getTenure() {
		return tenure;
	}

	public void setTenure(int tenure) {
		this.tenure = tenure;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public long incrementVersion() {
		return ++version;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

	public void setAcceptedAt(Instant acceptedAt) {
		this.acceptedAt = acceptedAt;
	}

	public Long getRowVersion() {
		return rowVersion;
	}

	public void setRowVersion(Long rowVersion) {
		this.rowVersion = rowVersion;
	}
}
