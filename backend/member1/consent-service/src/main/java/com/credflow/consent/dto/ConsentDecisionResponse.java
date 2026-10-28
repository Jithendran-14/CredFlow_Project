package com.credflow.consent.dto;

import java.util.UUID;

import com.credflow.consent.entity.ConsentStatus;

public record ConsentDecisionResponse(UUID operationId, UUID consentId, UUID applicationId, int consentVersion,
		ConsentStatus status, String orchestrationStatus) {
}