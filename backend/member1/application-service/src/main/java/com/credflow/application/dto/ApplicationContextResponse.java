package com.credflow.application.dto;

import java.time.LocalDate;

public record ApplicationContextResponse(String applicationId, int applicationRevision, String sellerId,
		String marketplaceId, String requestedAmount, String currency, String loanPurpose,
		BusinessProfileResponse businessProfile, String status) {

	public record BusinessProfileResponse(String displayName, String businessName, LocalDate businessStartDate,
			Phase0Details phase0) {
	}
}