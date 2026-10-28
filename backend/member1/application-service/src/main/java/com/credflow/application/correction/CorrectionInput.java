package com.credflow.application.correction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.credflow.application.dto.ApplicationRequest.BusinessProfile;
import com.credflow.application.dto.LoanPurpose;

public record CorrectionInput(@NotNull @Positive Integer expectedApplicationRevision,
		@NotNull @PositiveOrZero Integer expectedVersion,
		@NotBlank @Pattern(regexp = "[0-9]{1,9}\\.[0-9]{2}") String requestedAmount, @NotNull LoanPurpose loanPurpose,
		@NotNull @Valid BusinessProfile businessProfile) {
}
