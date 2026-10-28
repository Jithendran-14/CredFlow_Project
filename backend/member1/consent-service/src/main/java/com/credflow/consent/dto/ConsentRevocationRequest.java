package com.credflow.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConsentRevocationRequest(@NotNull @Min(1) Integer expectedConsentVersion,

		@NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,99}$") String reasonCode) {
}