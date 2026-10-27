package com.credflow.consent.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConsentDecisionRequest(
        @NotNull
        Decision decision,

        @NotNull
        @Min(0)
        Integer expectedConsentVersion,

        @NotBlank
        @Size(max = 100)
        String noticeVersion,

        @NotNull
        @Size(max = 3)
        Set<@NotNull Scope> scopes,

        @NotNull
        @Size(max = 1)
        Set<@NotNull UUID> sourceMarketplaceIds,

        @NotNull
        @Size(max = 20)
        Set<@NotNull UUID> recipientLenderIds
) {

    public enum Decision {
        GRANTED,
        DECLINED
    }

    public enum Scope {
        MARKETPLACE_DATA_READ,
        IDENTITY_VERIFICATION,
        LENDER_APPLICATION_SHARE
    }
}