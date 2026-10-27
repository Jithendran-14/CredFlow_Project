package com.credflow.consent.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityCheckResponse(
        UUID verificationRequestId,
        UUID applicationId,
        Integer applicationRevision,
        String fixtureIdentityId,
        String providerReference,
        Status status,
        String reasonCode,
        String message,
        OffsetDateTime checkedAt
) {

    public enum Status {
        VERIFIED,
        FAILED,
        PENDING,
        ACTION_REQUIRED
    }
}