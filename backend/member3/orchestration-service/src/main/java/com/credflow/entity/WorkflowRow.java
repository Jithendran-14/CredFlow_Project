package com.credflow.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity(name = "CfWorkflow")
@Table(name = "CF_WORKFLOW")
public class WorkflowRow {

    public WorkflowRow() {
    }

    @Id
    private UUID id;

    @Column(length = 255)
    private String sellerSubject;

    @Column(length = 120)
    private String marketplaceSellerId;

    @Column(length = 120)
    private String sellerId;

    private UUID marketplaceId;

    private int revision;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    private String currency;

    private String purpose;

    private String legalName;

    private String status;

    private String health;

    private String detail;

    private String lenderId;

    private String productId;

    @Lob
    private String profileJson;

    @Lob
    private String consentJson;

    @Lob
    private String decisionJson;

    @Lob
    private String financialPermissionJson;

    public String getFinancialPermissionJson(){return financialPermissionJson;}
    public void setFinancialPermissionJson(String value){financialPermissionJson=value;}

    @Lob
    private String eligibilityJson;

    @Lob
    private String originJson;

    @Lob
    private String healthIssuesJson;

    private long workflowVersion;

    private long lastIntegrationVersion;

    private UUID consentId;

    private int consentVersion;

    @Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.NUMERIC)
    private boolean consentGranted;

    private UUID verificationId;

    private UUID snapshotId;

    private UUID evaluationId;

    private LocalDate evaluationAsOfDate;

    @Column(length = 255)
    private String authorisedRetrievalReference;

    private UUID eligibilityOperationId;

    @Column(length = 255)
    private String eligibilityStatusUrl;

    private UUID eligibilityId;

    private UUID submissionId;

    private UUID cancellationId;

    @Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.NUMERIC)
    private boolean dispatchAuthorized;

    @Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.NUMERIC)
    private boolean revisionPending;

    private int expectedRevision;

    private int lastRefreshRevision;

    private UUID revisionOperation;

    private Instant updatedAt;

    @Version
    private Long rowVersion;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSellerSubject() {
        return sellerSubject;
    }

    public void setSellerSubject(String sellerSubject) {
        this.sellerSubject = sellerSubject;
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

    public UUID getMarketplaceId() {
        return marketplaceId;
    }

    public void setMarketplaceId(UUID marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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

    public String getProfileJson() {
        return profileJson;
    }

    public void setProfileJson(String profileJson) {
        this.profileJson = profileJson;
    }

    public String getConsentJson() {
        return consentJson;
    }

    public void setConsentJson(String consentJson) {
        this.consentJson = consentJson;
    }

    public String getDecisionJson() {
        return decisionJson;
    }

    public void setDecisionJson(String decisionJson) {
        this.decisionJson = decisionJson;
    }

    public String getEligibilityJson() {
        return eligibilityJson;
    }

    public void setEligibilityJson(String eligibilityJson) {
        this.eligibilityJson = eligibilityJson;
    }

    public String getOriginJson() {
        return originJson;
    }

    public void setOriginJson(String originJson) {
        this.originJson = originJson;
    }

    public String getHealthIssuesJson() {
        return healthIssuesJson;
    }

    public void setHealthIssuesJson(String healthIssuesJson) {
        this.healthIssuesJson = healthIssuesJson;
    }

    public long getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(long workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public long incrementWorkflowVersion() {
        return ++workflowVersion;
    }

    public long getLastIntegrationVersion() {
        return lastIntegrationVersion;
    }

    public void setLastIntegrationVersion(long lastIntegrationVersion) {
        this.lastIntegrationVersion = lastIntegrationVersion;
    }

    public UUID getConsentId() {
        return consentId;
    }

    public void setConsentId(UUID consentId) {
        this.consentId = consentId;
    }

    public int getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(int consentVersion) {
        this.consentVersion = consentVersion;
    }

    public boolean isConsentGranted() {
        return consentGranted;
    }

    public void setConsentGranted(boolean consentGranted) {
        this.consentGranted = consentGranted;
    }

    public UUID getVerificationId() {
        return verificationId;
    }

    public void setVerificationId(UUID verificationId) {
        this.verificationId = verificationId;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public void setEvaluationId(UUID evaluationId) {
        this.evaluationId = evaluationId;
    }

    public LocalDate getEvaluationAsOfDate() {
        return evaluationAsOfDate;
    }

    public void setEvaluationAsOfDate(LocalDate evaluationAsOfDate) {
        this.evaluationAsOfDate = evaluationAsOfDate;
    }

    public String getAuthorisedRetrievalReference() {
        return authorisedRetrievalReference;
    }

    public void setAuthorisedRetrievalReference(String authorisedRetrievalReference) {
        this.authorisedRetrievalReference = authorisedRetrievalReference;
    }

    public UUID getEligibilityOperationId() {
        return eligibilityOperationId;
    }

    public void setEligibilityOperationId(UUID eligibilityOperationId) {
        this.eligibilityOperationId = eligibilityOperationId;
    }

    public String getEligibilityStatusUrl() {
        return eligibilityStatusUrl;
    }

    public void setEligibilityStatusUrl(String eligibilityStatusUrl) {
        this.eligibilityStatusUrl = eligibilityStatusUrl;
    }

    public UUID getEligibilityId() {
        return eligibilityId;
    }

    public void setEligibilityId(UUID eligibilityId) {
        this.eligibilityId = eligibilityId;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(UUID submissionId) {
        this.submissionId = submissionId;
    }

    public UUID getCancellationId() {
        return cancellationId;
    }

    public void setCancellationId(UUID cancellationId) {
        this.cancellationId = cancellationId;
    }

    public boolean isDispatchAuthorized() {
        return dispatchAuthorized;
    }

    public void setDispatchAuthorized(boolean dispatchAuthorized) {
        this.dispatchAuthorized = dispatchAuthorized;
    }

    public boolean isRevisionPending() {
        return revisionPending;
    }

    public void setRevisionPending(boolean revisionPending) {
        this.revisionPending = revisionPending;
    }

    public int getExpectedRevision() {
        return expectedRevision;
    }

    public void setExpectedRevision(int expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    public int getLastRefreshRevision() {
        return lastRefreshRevision;
    }

    public void setLastRefreshRevision(int lastRefreshRevision) {
        this.lastRefreshRevision = lastRefreshRevision;
    }

    public UUID getRevisionOperation() {
        return revisionOperation;
    }

    public void setRevisionOperation(UUID revisionOperation) {
        this.revisionOperation = revisionOperation;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
