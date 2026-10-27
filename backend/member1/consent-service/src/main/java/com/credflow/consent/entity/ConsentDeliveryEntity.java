package com.credflow.consent.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_CONSENT_DELIVERIES")
public class ConsentDeliveryEntity {

    @Id
    @Column(name = "OPERATION_ID", length = 36,
            nullable = false, updatable = false)
    private String operationId;

    @Column(name = "DELIVERY_STATUS", length = 20, nullable = false)
    private String deliveryStatus;

    @Column(name = "ATTEMPT_COUNT", nullable = false)
    private Integer attemptCount;

    @Column(name = "NEXT_ATTEMPT_AT", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "CLAIM_TOKEN", length = 36)
    private String claimToken;

    @Column(name = "LEASE_EXPIRES_AT")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "LAST_HTTP_STATUS")
    private Integer lastHttpStatus;

    @Column(name = "LAST_ERROR", length = 1000)
    private String lastError;

    @Column(name = "CREATED_AT", nullable = false,
            insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "APPLIED_AT")
    private OffsetDateTime appliedAt;

    protected ConsentDeliveryEntity() {
        // Required by JPA.
    }

    public static ConsentDeliveryEntity pending(UUID operationId) {
        ConsentDeliveryEntity delivery = new ConsentDeliveryEntity();

        delivery.operationId = operationId.toString();
        delivery.deliveryStatus = "PENDING";
        delivery.attemptCount = 0;
        delivery.nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC);

        return delivery;
    }

    @PrePersist
    protected void onCreate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getOperationId() {
        return operationId;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public OffsetDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getAppliedAt() {
        return appliedAt;
    }
}