package com.credflow.consent.dto;

import java.util.UUID;

public record VerificationAcceptedResponse(
        UUID verificationId,
        UUID applicationId,
        int applicationRevision,
        String status
) {
}