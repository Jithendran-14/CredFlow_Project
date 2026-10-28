package com.credflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "CfInformation")
@Table(name = "CF_INFORMATION_REQUEST")
public class InformationRow {

	public InformationRow() {
	}

	@Id
	private UUID id;

	private UUID applicationId;

	private UUID submissionId;

	private String status;

	private String acknowledgement;

	@Lob
	private String questionsJson;

	@Lob
	private String answersJson;

	@Lob
	private String documentsJson;

	private long version;

	private Instant createdAt;

	private Instant answeredAt;

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

	public UUID getSubmissionId() {
		return submissionId;
	}

	public void setSubmissionId(UUID submissionId) {
		this.submissionId = submissionId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getAcknowledgement() {
		return acknowledgement;
	}

	public void setAcknowledgement(String acknowledgement) {
		this.acknowledgement = acknowledgement;
	}

	public String getQuestionsJson() {
		return questionsJson;
	}

	public void setQuestionsJson(String questionsJson) {
		this.questionsJson = questionsJson;
	}

	public String getAnswersJson() {
		return answersJson;
	}

	public void setAnswersJson(String answersJson) {
		this.answersJson = answersJson;
	}

	public String getDocumentsJson() {
		return documentsJson;
	}

	public void setDocumentsJson(String documentsJson) {
		this.documentsJson = documentsJson;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public long incrementVersion() {
		return ++version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getAnsweredAt() {
		return answeredAt;
	}

	public void setAnsweredAt(Instant answeredAt) {
		this.answeredAt = answeredAt;
	}

	public Long getRowVersion() {
		return rowVersion;
	}

	public void setRowVersion(Long rowVersion) {
		this.rowVersion = rowVersion;
	}
}
