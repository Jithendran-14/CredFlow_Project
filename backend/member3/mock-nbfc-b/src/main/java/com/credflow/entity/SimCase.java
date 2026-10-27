package com.credflow.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "CF_SIM_CASE")
public class SimCase {

    @Id
    @Column(name = "CASE_KEY", length = 120)
    private String id;

    @Column(name = "LENDER_CODE", nullable = false, length = 20)
    private String lenderCode;

    @Column(name = "LENDER_ID", nullable = false, length = 80)
    private String lenderId;

    @Column(name = "SUBMISSION_ID", nullable = false)
    private UUID submissionId;

    @Column(name = "APPLICATION_ID")
    private UUID applicationId;

    @Column(name = "APPLICATION_REVISION")
    private Integer revision;

    @Column(name = "EXTERNAL_ID", length = 100)
    private String externalId;

    @Column(name = "PAYLOAD_HASH", length = 64)
    private String payloadHash;

    @Column(name = "STATUS", nullable = false, length = 40)
    private String status;

    @Column(name = "ACCEPTED_AT")
    private Instant acceptedAt;

    @Lob
    @Column(name = "STATE_JSON")
    private String stateJson;

    @Column(name = "CALLBACK_SEQUENCE", nullable = false)
    private long sequence;

    @Column(name = "REQUESTED_AMOUNT", precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "OFFERED_AMOUNT", precision = 19, scale = 2)
    private BigDecimal offeredAmount;

    @Column(name = "OFFER_FEES", precision = 19, scale = 2)
    private BigDecimal offerFees;

    @Column(name = "OFFER_RATE", precision = 9, scale = 4)
    private BigDecimal offerRate;

    // Request-local trace while holding this case's transaction lock. Every
    // emitted callback persists these values in its immutable payload JSON.
    @Transient
    private String commandCorrelationId;

    @Transient
    private UUID commandCausationId;

    @Version
    @Column(name = "ROW_VERSION")
    private Long version;

    public SimCase() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
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

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public void setRequestedAmount(BigDecimal requestedAmount) {
        this.requestedAmount = requestedAmount;
    }

    public BigDecimal getOfferedAmount() {
        return offeredAmount;
    }

    public void setOfferedAmount(BigDecimal offeredAmount) {
        this.offeredAmount = offeredAmount;
    }

    public BigDecimal getOfferFees() {
        return offerFees;
    }

    public void setOfferFees(BigDecimal offerFees) {
        this.offerFees = offerFees;
    }

    public BigDecimal getOfferRate() {
        return offerRate;
    }

    public void setOfferRate(BigDecimal offerRate) {
        this.offerRate = offerRate;
    }

    public String getCommandCorrelationId() {
        return commandCorrelationId;
    }

    public void setCommandCorrelationId(String commandCorrelationId) {
        this.commandCorrelationId = commandCorrelationId;
    }

    public UUID getCommandCausationId() {
        return commandCausationId;
    }

    public void setCommandCausationId(UUID commandCausationId) {
        this.commandCausationId = commandCausationId;
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
