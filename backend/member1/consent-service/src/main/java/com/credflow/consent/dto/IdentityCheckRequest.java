package com.credflow.consent.dto;

import java.util.UUID;

public record IdentityCheckRequest(UUID verificationRequestId, UUID applicationId, int applicationRevision,
		String fixtureIdentityId) {
}