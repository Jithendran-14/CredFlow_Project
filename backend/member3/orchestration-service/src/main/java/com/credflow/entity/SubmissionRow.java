package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "CfSubmission")
@Table(name = "CF_SUBMISSION", uniqueConstraints = @UniqueConstraint(columnNames = "APPLICATION_ID"))
public class SubmissionRow {

    public SubmissionRow() {
    }

    @Id
    private UUID id;

    @Column(name = "APPLICATION_ID")
    private UUID applicationId;

    private String lenderId;

    private String productId;

    private String status;

    private String externalId;

    private String cancellationAcknowledgement;

    private int revision;

    private Instant createdAt;

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

    public String getLenderId() {
        return lenderId;
    }

    public void setLenderId(String lenderId) {
        this.lenderId = lenderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getCancellationAcknowledgement() {
        return cancellationAcknowledgement;
    }

    public void setCancellationAcknowledgement(String cancellationAcknowledgement) {
        this.cancellationAcknowledgement = cancellationAcknowledgement;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
