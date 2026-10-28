package com.credflow.consent.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerificationRequest(@NotNull UUID verificationId,

		@NotNull UUID applicationId,

		@NotNull @Min(1) Integer applicationRevision,

		@NotNull UUID consentId,

		@NotNull @Min(1) Integer consentVersion,

		@NotNull @Valid OriginActor originActor,

		@Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String correlationId,

		UUID causationId) {

	public record OriginActor(@NotBlank @Pattern(regexp = "HUMAN|SYSTEM_CLIENT|SYSTEM") String type,

			@NotBlank @Size(max = 255) String subject,

			UUID organisationId) {
	}
}