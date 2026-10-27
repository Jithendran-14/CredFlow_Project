package com.credflow.consent.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FinancialWithdrawalResponse(
        UUID operationId,
        UUID financialConsentId,
        int financialConsentVersion,
        UUID applicationId,
        String status,
        OffsetDateTime revokedAt
) {
}