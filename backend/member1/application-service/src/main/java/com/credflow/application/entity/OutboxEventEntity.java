
package com.credflow.application.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_OUTBOX_EVENTS")
public class OutboxEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "OUTBOX_EVENT_ID", nullable = false, updatable = false)
	private Long outboxEventId;

	@Column(name = "EVENT_ID", length = 36, nullable = false, unique = true)
	private String eventId;

	@Column(name = "AGGREGATE_TYPE", length = 50, nullable = false)
	private String aggregateType;

	@Column(name = "AGGREGATE_ID", length = 36, nullable = false)
	private String aggregateId;

	@Column(name = "EVENT_TYPE", length = 100, nullable = false)
	private String eventType;

	@Column(name = "EVENT_VERSION", nullable = false)
	private Integer eventVersion = 1;

	@Lob
	@Column(name = "EVENT_PAYLOAD", nullable = false, columnDefinition = "CLOB")
	private String eventPayload;

	@Column(name = "EVENT_STATUS", length = 20, nullable = false)
	private String eventStatus = "PENDING";

	@Column(name = "RETRY_COUNT", nullable = false)
	private Integer retryCount = 0;

	@Column(name = "NEXT_ATTEMPT_AT")
	private OffsetDateTime nextAttemptAt;

	@Column(name = "CREATED_AT", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "PUBLISHED_AT")
	private OffsetDateTime publishedAt;

	@Column(name = "CLAIM_TOKEN", length = 36)
	private String claimToken;

	@Column(name = "CLAIMED_AT")
	private OffsetDateTime claimedAt;

	@Column(name = "LEASE_EXPIRES_AT")
	private OffsetDateTime leaseExpiresAt;

	protected OutboxEventEntity() {
		// Required by JPA.
	}

	public static OutboxEventEntity pending(String eventId, String aggregateType, String aggregateId, String eventType,
			int eventVersion, String eventPayload) {

		OutboxEventEntity event = new OutboxEventEntity();

		event.eventId = eventId;
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.eventVersion = eventVersion;
		event.eventPayload = eventPayload;
		event.eventStatus = "PENDING";
		event.retryCount = 0;

		return event;
	}

	public Long getOutboxEventId() {
		return outboxEventId;
	}

	public String getEventId() {
		return eventId;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public String getAggregateId() {
		return aggregateId;
	}

	public String getEventType() {
		return eventType;
	}

	public Integer getEventVersion() {
		return eventVersion;
	}

	public String getEventPayload() {
		return eventPayload;
	}

	public String getEventStatus() {
		return eventStatus;
	}

	public Integer getRetryCount() {
		return retryCount;
	}

	public OffsetDateTime getNextAttemptAt() {
		return nextAttemptAt;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getPublishedAt() {
		return publishedAt;
	}

	public void markProcessing() {
		this.eventStatus = "PROCESSING";
	}

	public void markPublished() {
		this.eventStatus = "PUBLISHED";
		this.publishedAt = OffsetDateTime.now();
		this.nextAttemptAt = null;
	}

	public void markFailed(OffsetDateTime nextAttemptAt) {
		this.eventStatus = "FAILED";
		this.retryCount++;
		this.nextAttemptAt = nextAttemptAt;
	}

	public String getClaimToken() {
		return claimToken;
	}

	public OffsetDateTime getClaimedAt() {
		return claimedAt;
	}

	public OffsetDateTime getLeaseExpiresAt() {
		return leaseExpiresAt;
	}
}
