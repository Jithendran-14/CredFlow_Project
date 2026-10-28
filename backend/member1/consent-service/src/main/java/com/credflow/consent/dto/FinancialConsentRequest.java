package com.credflow.consent.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FinancialConsentRequest(@NotNull @Min(1) Integer applicationRevision,

		@NotNull @Min(1) Integer expectedOperationalConsentVersion,

		@NotNull @Min(0) Integer expectedFinancialConsentVersion,

		@NotBlank @Size(max = 100) String noticeVersion,

		@NotNull @AssertTrue Boolean accepted) {
}