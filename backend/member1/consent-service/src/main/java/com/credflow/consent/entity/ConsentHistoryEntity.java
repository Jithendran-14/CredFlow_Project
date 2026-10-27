package com.credflow.consent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_CONSENT_HISTORY")
public class ConsentHistoryEntity {

    @EmbeddedId
    private ConsentHistoryId id;

    @Column(
            name = "OPERATION_ID",
            length = 36,
            nullable = false,
            unique = true
    )
    private String operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "CONSENT_STATUS", length = 20, nullable = false)
    private ConsentStatus consentStatus;

    @Column(name = "ACTOR_SUBJECT", length = 255, nullable = false)
    private String actorSubject;

    @Column(name = "CORRELATION_ID", length = 100, nullable = false)
    private String correlationId;

    @Column(name = "REQUEST_HASH", length = 64, nullable = false)
    private String requestHash;

    @Lob
    @Column(
            name = "CONSENT_PAYLOAD",
            nullable = false,
            columnDefinition = "CLOB"
    )
    private String consentPayload;

    @Column(
            name = "RECORDED_AT",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime recordedAt;

    protected ConsentHistoryEntity() {
        // Required by JPA.
    }

    public ConsentHistoryEntity(
            String consentId,
            int consentVersion,
            UUID operationId,
            ConsentStatus consentStatus,
            String actorSubject,
            String correlationId,
            String requestHash,
            String consentPayload) {

        this.id = new ConsentHistoryId(consentId, consentVersion);
        this.operationId = operationId.toString();
        this.consentStatus = consentStatus;
        this.actorSubject = actorSubject;
        this.correlationId = correlationId;
        this.requestHash = requestHash;
        this.consentPayload = consentPayload;
    }

    public ConsentHistoryId getId() {
        return id;
    }

    public String getOperationId() {
        return operationId;
    }

    public ConsentStatus getConsentStatus() {
        return consentStatus;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getConsentPayload() {
        return consentPayload;
    }
}