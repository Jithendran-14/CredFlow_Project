package com.credflow.consent.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FinancialConsentResponse(
        UUID operationId,
        UUID financialConsentId,
        int financialConsentVersion,
        UUID applicationId,
        int applicationRevision,
        UUID operationalConsentId,
        int operationalConsentVersion,
        String scope,
        String status,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt
) {
}