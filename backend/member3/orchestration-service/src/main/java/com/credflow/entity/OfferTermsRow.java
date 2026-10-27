package com.credflow.entity;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
@Entity(name="CfOfferTerms") @Table(name="CF_OFFER_TERMS_DOCUMENT")
public class OfferTermsRow {
 @Id @Column(name="OFFER_ID") private UUID offerId;
 @Column(name="APPLICATION_ID",nullable=false) private UUID applicationId;
 @Column(name="SUBMISSION_ID",nullable=false) private UUID submissionId;
 @Column(name="LENDER_ID",length=100,nullable=false) private String lenderId;
 @Column(name="TERMS_REFERENCE",length=255,nullable=false) private String termsReference;
 @Column(name="FILE_NAME",length=150,nullable=false) private String fileName;
 @Column(name="SHA256",length=64,nullable=false) private String sha256;
 @Column(name="FILE_SIZE",nullable=false) private long fileSize;
 @Lob @Column(name="PDF_BYTES",nullable=false) private byte[] content;
 @Column(name="UPLOADED_BY",length=100,nullable=false) private String uploadedBy;
 @Column(name="UPLOADED_AT",nullable=false) private Instant uploadedAt;
 public UUID getOfferId(){return offerId;}
 public void setOfferId(UUID value){this.offerId=value;}
 public UUID getApplicationId(){return applicationId;}
 public void setApplicationId(UUID value){this.applicationId=value;}
 public UUID getSubmissionId(){return submissionId;}
 public void setSubmissionId(UUID value){this.submissionId=value;}
 public String getLenderId(){return lenderId;}
 public void setLenderId(String value){this.lenderId=value;}
 public String getTermsReference(){return termsReference;}
 public void setTermsReference(String value){this.termsReference=value;}
 public String getFileName(){return fileName;}
 public void setFileName(String value){this.fileName=value;}
 public String getSha256(){return sha256;}
 public void setSha256(String value){this.sha256=value;}
 public long getFileSize(){return fileSize;}
 public void setFileSize(long value){this.fileSize=value;}
 public byte[] getContent(){return content;}
 public void setContent(byte[] value){this.content=value;}
 public String getUploadedBy(){return uploadedBy;}
 public void setUploadedBy(String value){this.uploadedBy=value;}
 public Instant getUploadedAt(){return uploadedAt;}
 public void setUploadedAt(Instant value){this.uploadedAt=value;}
}
