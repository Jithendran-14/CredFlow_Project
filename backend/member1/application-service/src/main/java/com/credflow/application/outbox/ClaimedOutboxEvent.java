package com.credflow.application.outbox;

public record ClaimedOutboxEvent(
        Long outboxEventId,
        String claimToken,
        String eventId,
        String aggregateId,
        String eventType,
        String eventPayload,
        int retryCount
) {
}