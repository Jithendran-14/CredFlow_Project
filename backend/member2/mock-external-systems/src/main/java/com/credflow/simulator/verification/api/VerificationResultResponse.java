package com.credflow.simulator.verification.api;

import com.credflow.simulator.verification.VerificationSimulator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

/** Maps the fictional provider result to the verification HTTP response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerificationResultResponse(UUID verificationRequestId, UUID applicationId, int applicationRevision,
		String fixtureIdentityId, String providerReference, String status, String reasonCode, String message,
		String checkedAt) {
	public static VerificationResultResponse from(VerificationSimulator.Result result) {
		Objects.requireNonNull(result, "Provider result is required");
		return new VerificationResultResponse(result.verificationRequestId(), result.applicationId(),
				result.applicationRevision(), result.fixtureIdentityId(), result.providerReference(),
				result.status().name(), result.reasonCode(), result.message(), result.checkedAt().toString());
	}
}
