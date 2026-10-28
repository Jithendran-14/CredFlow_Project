package com.credflow.auth.marketplace;

import jakarta.validation.constraints.*;

public record LoanRequestInput(@NotBlank @Pattern(regexp = "^[0-9]{1,9}\\.[0-9]{2}$") String requestedAmount,
		@NotBlank @Pattern(regexp = "WORKING_CAPITAL|PURCHASE_INVENTORY|BUSINESS_EXPANSION") String loanPurpose,
		@NotNull @AssertTrue(message = "Authorise initial application-data sharing before submitting.") Boolean initialSharingAuthorised,
		@NotNull @jakarta.validation.Valid Phase0Details phase0) {
	public LoanRequestInput(String amount, String purpose, Boolean sharing) {
		this(amount, purpose, sharing, null);
	}
}
