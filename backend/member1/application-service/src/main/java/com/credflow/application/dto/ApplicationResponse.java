package com.credflow.application.dto;

import java.time.LocalDate;

public record ApplicationResponse(
        String applicationId,
        String applicationReference,
        String sellerId,
        String marketplaceId,
        String requestedAmount,
        String currency,
        LoanPurpose loanPurpose,
        String status,
        int applicationRevision,
        int version,
        int workflowVersion,
        BusinessProfileResponse businessProfile
) {

    public record BusinessProfileResponse(
            String displayName,
            String businessName,
            LocalDate businessStartDate,
            Phase0Details phase0
    ) {
    }
}