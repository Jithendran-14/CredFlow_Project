package com.credflow.application.dto;

import java.time.LocalDate;

public record ApplicationSubmittedEvent(
        String eventId,
        String eventType,
        String schemaVersion,
        String occurredAt,
        String producer,
        String applicationId,
        long aggregateVersion,
        String correlationId,
        Data data,
        Actor actor,
        Actor originActor
) {

    public record Actor(
            String type,
            String subject,
            String organisationId
    ) {
    }

    public record Data(
            String sellerId,
            String marketplaceId,
            String marketplaceSellerId,
            String requestedAmount,
            String currency,
            String loanPurpose,
            String status,
            int applicationRevision,
            String sellerRequestReference,
            BusinessProfile businessProfile
    ) {
    }

    public record BusinessProfile(
            String displayName,
            String businessName,
            LocalDate businessStartDate,
            Phase0Details phase0
    ) {
    }
}