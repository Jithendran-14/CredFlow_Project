
package com.credflow.application.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "CF_APPLICATIONS")
public class ApplicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "APPLICATION_ID", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "PUBLIC_APPLICATION_ID",
            length = 36, nullable = false, updatable = false, unique = true)
    private String publicApplicationId;

    @Column(name = "APPLICATION_REFERENCE",
            length = 40, nullable = false, unique = true)
    private String applicationReference;

    @Column(name = "MARKETPLACE_ID", length = 36, nullable = false)
    private String marketplaceId;

    @Column(name = "MARKETPLACE_SELLER_ID",
            length = 100, nullable = false)
    private String marketplaceSellerId;

    @Column(name = "MARKETPLACE_REQUEST_ID",
            length = 100, nullable = false)
    private String marketplaceRequestId;

    @Column(name = "SELLER_ID", length = 100)
    private String sellerId;
    
    @Column(name = "SELLER_REQUEST_REFERENCE",
            length = 100, nullable = false)
    private String sellerRequestReference;

    @Column(name = "REQUESTED_AMOUNT",
            precision = 19, scale = 2, nullable = false)
    private BigDecimal requestedAmount;

    @Column(name = "CURRENCY", length = 3, nullable = false)
    private String currency = "INR";

    @Column(name = "LOAN_PURPOSE", length = 30, nullable = false)
    private String loanPurpose;

    @Column(name = "DISPLAY_NAME", length = 100, nullable = false)
    private String displayName;

    @Column(name = "BUSINESS_NAME", length = 200, nullable = false)
    private String businessName;

    @Column(name = "BUSINESS_START_DATE", nullable = false)
    private LocalDate businessStartDate;

    @Column(name = "STATUS", length = 30, nullable = false)
    private String status = "DRAFT";

    @Column(name = "APPLICATION_REVISION", nullable = false)
    private Integer applicationRevision = 1;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @Column(name = "WORKFLOW_VERSION", nullable = false)
    private Integer workflowVersion = 0;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    @jakarta.persistence.Lob
    @Column(name="PHASE0_JSON")
    private String phase0Json;
    public String getPhase0Json(){return phase0Json;}
    public void setPhase0Json(String value){phase0Json=value;}

    public ApplicationEntity() {
        // Required by JPA.
    }
    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        createdAt = now;
        updatedAt = now;

        if (currency == null) {
            currency = "INR";
        }

        if (status == null) {
            status = "DRAFT";
        }

        if (applicationRevision == null) {
            applicationRevision = 1;
        }

        if (workflowVersion == null) {
            workflowVersion = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getPublicApplicationId() {
        return publicApplicationId;
    }

    public void assignPublicApplicationId(String publicApplicationId) {
        if (this.publicApplicationId != null) {
            throw new IllegalStateException(
                    "Public application ID has already been assigned");
        }

        if (publicApplicationId == null || publicApplicationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Public application ID cannot be blank");
        }

        this.publicApplicationId = publicApplicationId;
    }

    public String getApplicationReference() {
        return applicationReference;
    }

    public void setApplicationReference(String applicationReference) {
        this.applicationReference = applicationReference;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public void setMarketplaceId(String marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public String getMarketplaceSellerId() {
        return marketplaceSellerId;
    }

    public void setMarketplaceSellerId(String marketplaceSellerId) {
        this.marketplaceSellerId = marketplaceSellerId;
    }
    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }
    public String getMarketplaceRequestId() {
        return marketplaceRequestId;
    }

    public void setMarketplaceRequestId(String marketplaceRequestId) {
        this.marketplaceRequestId = marketplaceRequestId;
    }

    public String getSellerRequestReference() {
        return sellerRequestReference;
    }

    public void setSellerRequestReference(String sellerRequestReference) {
        this.sellerRequestReference = sellerRequestReference;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public void setRequestedAmount(BigDecimal requestedAmount) {
        this.requestedAmount = requestedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getLoanPurpose() {
        return loanPurpose;
    }

    public void setLoanPurpose(String loanPurpose) {
        this.loanPurpose = loanPurpose;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public LocalDate getBusinessStartDate() {
        return businessStartDate;
    }

    public void setBusinessStartDate(LocalDate businessStartDate) {
        this.businessStartDate = businessStartDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getApplicationRevision() {
        return applicationRevision;
    }

    public void setApplicationRevision(Integer applicationRevision) {
        this.applicationRevision = applicationRevision;
    }

    public Integer getVersion() {
        return version;
    }

    public Integer getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(Integer workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
