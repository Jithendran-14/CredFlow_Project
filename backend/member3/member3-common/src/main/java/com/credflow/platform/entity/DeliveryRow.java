package com.credflow.platform.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "CF_M3_DELIVERY")
public class DeliveryRow {

    @Id
    @Column(name = "DELIVERY_ID", length = 64)
    private String id;

    @Column(name = "KIND", nullable = false, length = 12)
    private String kind;

    @Column(name = "APPLICATION_ID", nullable = false)
    private UUID applicationId;

    @Column(name = "TARGET_NAME", nullable = false, length = 160)
    private String target;

    @Lob
    @Column(name = "PAYLOAD_JSON", nullable = false)
    private String payload;

    @Column(name = "PAYLOAD_HASH", nullable = false, length = 64)
    private String hash;

    @Column(name = "POSITION_NO", nullable = false)
    private long position;

    @Column(name = "STATUS", nullable = false, length = 12)
    private String status;

    @Column(name = "ATTEMPTS", nullable = false)
    private int attempts;

    @Column(name = "NEXT_ATTEMPT_AT", nullable = false)
    private Instant nextAttempt;

    @Column(name = "LEASE_UNTIL")
    private Instant leaseUntil;

    @Column(name = "LEASE_TOKEN")
    private UUID leaseToken;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "LAST_ERROR", length = 120)
    private String error;

    @Version
    @Column(name = "ROW_VERSION")
    private Long rowVersion;

    public DeliveryRow() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
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

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
