
package com.credflow.application.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApplicationRequest(

        @NotNull(message = "Marketplace ID is required")
        UUID marketplaceId,

        @NotBlank(message = "Marketplace seller ID is required")
        @Size(max = 100)
        String marketplaceSellerId,

        @NotBlank(message = "Marketplace request ID is required")
        @Size(max = 100)
        String marketplaceRequestId,

        @NotBlank(message = "Requested amount is required")
        @Pattern(
            regexp = "^[0-9]{1,17}\\.[0-9]{2}$",
            message = "Amount must contain two decimal places, e.g. 500000.00"
        )
        String requestedAmount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^INR$", message = "Only INR is supported")
        String currency,

        @NotNull(message = "Loan purpose is required")
        LoanPurpose loanPurpose,

        @NotNull(message = "Business profile is required")
        @Valid
        BusinessProfile businessProfile,

        @NotBlank(message = "Seller request reference is required")
        @Size(max = 100)
        String sellerRequestReference,

        @NotNull(message = "Initial sharing consent is required")
        @Valid
        InitialSharingConsent initialSharingConsent
) {

    public record BusinessProfile(

            @NotBlank(message = "Display name is required")
            @Size(max = 100)
            String displayName,

            @NotBlank(message = "Business name is required")
            @Size(max = 200)
            String businessName,

            @NotNull(message = "Business start date is required")
            @jakarta.validation.constraints.PastOrPresent(
                message = "Business start date cannot be in the future"
            )
            java.time.LocalDate businessStartDate,
            @NotNull @Valid Phase0Details phase0
    ) { public BusinessProfile(String displayName,String businessName,java.time.LocalDate date){this(displayName,businessName,date,null);}
    }

    public record InitialSharingConsent(

            @NotBlank(message = "Consent scope is required")
            @Pattern(
                regexp = "^APPLICATION_SHARE_WITH_CREDFLOW$",
                message = "Unsupported consent scope"
            )
            String scope,

            @NotBlank(message = "Notice version is required")
            @Size(max = 30)
            String noticeVersion,

            @NotNull(message = "Consent authorization time is required")
            @jakarta.validation.constraints.PastOrPresent(
                message = "Consent authorization time cannot be in the future"
            )
            java.time.OffsetDateTime authorisedAt,

            @NotBlank(message = "Consent evidence reference is required")
            @Size(max = 100)
            String evidenceReference
    ) {
    }
}
