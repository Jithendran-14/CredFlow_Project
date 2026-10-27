package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "IntegrationCallback")
@Table(name = "CF_INT_CALLBACK", uniqueConstraints = @UniqueConstraint(name = "UK_INT_CB_SEQUENCE", columnNames = { "SUBMISSION_ID", "LENDER_SEQUENCE" }))
public class IntegrationCallback {

    @Id
    private UUID eventId;

    @Column(name = "SUBMISSION_ID", nullable = false)
    private UUID submissionId;

    @Column(name = "LENDER_SEQUENCE", nullable = false)
    private long lenderSequence;

    @Column(nullable = false, length = 64)
    private String payloadHash;

    @Lob
    @Column(nullable = false)
    private String callbackJson;

    @Lob
    @Column(nullable = false)
    private String actorJson;

    @Column(nullable = false, length = 100)
    private String correlationId;

    @Column(nullable = false, length = 12)
    private String status = "PENDING";

    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();

    private int attempts;

    @Column(length = 300)
    private String failureCode;

    @Version
    private Long rowVersion;

    public IntegrationCallback() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(UUID submissionId) {
        this.submissionId = submissionId;
    }

    public long getLenderSequence() {
        return lenderSequence;
    }

    public void setLenderSequence(long lenderSequence) {
        this.lenderSequence = lenderSequence;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getCallbackJson() {
        return callbackJson;
    }

    public void setCallbackJson(String callbackJson) {
        this.callbackJson = callbackJson;
    }

    public String getActorJson() {
        return actorJson;
    }

    public void setActorJson(String actorJson) {
        this.actorJson = actorJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
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
