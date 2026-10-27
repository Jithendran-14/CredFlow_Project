package com.credflow.consent.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "CF_CONSENTS")
public class ConsentEntity {

    @Id
    @Column(name = "CONSENT_ID", length = 36, nullable = false)
    private String consentId;

    @Column(
            name = "APPLICATION_ID",
            length = 36,
            nullable = false,
            unique = true
    )
    private String applicationId;

    @Column(name = "SELLER_ID", length = 100, nullable = false)
    private String sellerId;

    @Column(name = "CONSENT_VERSION", nullable = false)
    private Integer consentVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "CONSENT_STATUS", length = 20, nullable = false)
    private ConsentStatus consentStatus;

    @Lob
    @Column(
            name = "CONSENT_PAYLOAD",
            nullable = false,
            columnDefinition = "CLOB"
    )
    private String consentPayload;

    @Column(name = "ORCHESTRATION_APPLIED_VERSION", nullable = false)
    private Integer orchestrationAppliedVersion = 0;

    @Version
    @Column(name = "ROW_VERSION", nullable = false)
    private Long rowVersion;

    @Column(
            name = "CREATED_AT",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    protected ConsentEntity() {
        // Required by JPA.
    }

    public ConsentEntity(
            UUID consentId,
            UUID applicationId,
            String sellerId,
            ConsentStatus consentStatus,
            String consentPayload) {

        this.consentId = consentId.toString();
        this.applicationId = applicationId.toString();
        this.sellerId = sellerId;
        this.consentStatus = consentStatus;
        this.consentPayload = consentPayload;
    }

    @PrePersist
    protected void onCreate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getConsentId() {
        return consentId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public Integer getConsentVersion() {
        return consentVersion;
    }

    public ConsentStatus getConsentStatus() {
        return consentStatus;
    }

    public String getConsentPayload() {
        return consentPayload;
    }

    public Integer getOrchestrationAppliedVersion() {
        return orchestrationAppliedVersion;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
    public void revoke(String payload) {

        if (consentStatus != ConsentStatus.GRANTED) {
            throw new IllegalStateException(
                    "Only granted consent can be revoked"
            );
        }

        consentVersion = Math.addExact(consentVersion, 1);
        consentStatus = ConsentStatus.REVOKED;
        consentPayload = payload;
    }
}