package com.credflow.consent.dto;

import java.util.UUID;

public record VerificationPrecheckResult(Outcome outcome, String reasonCode, String sellerId, UUID marketplaceId) {

	public enum Outcome {
		READY_FOR_IDENTITY_CHECK, REJECTED, RETRY_LATER
	}

	public static VerificationPrecheckResult ready(String sellerId, UUID marketplaceId) {

		return new VerificationPrecheckResult(Outcome.READY_FOR_IDENTITY_CHECK, null, sellerId, marketplaceId);
	}

	public static VerificationPrecheckResult rejected(String reason) {
		return new VerificationPrecheckResult(Outcome.REJECTED, reason, null, null);
	}

	public static VerificationPrecheckResult retry(String reason) {
		return new VerificationPrecheckResult(Outcome.RETRY_LATER, reason, null, null);
	}
}