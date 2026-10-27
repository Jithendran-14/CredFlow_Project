package com.credflow.consent.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class FinancialDataModels {

    private FinancialDataModels() {
    }

    public record Request(
            @NotNull
            @Min(1)
            Integer applicationRevision,

            @NotNull
            UUID verificationId,

            @NotNull
            LocalDate asOfDate,

            @NotBlank
            @Size(max = 255)
            String authorisedRetrievalReference
    ) {
    }

    public record ProviderRequest(
            UUID requestId,
            String sellerFixtureId,
            String dataScope,
            LocalDate asOfDate,
            LocalDate periodStart,
            LocalDate periodEndExclusive
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Month(
            String month,
            String status,
            String grossSalesAmount,
            String returnsAmount,
            String payoutAmount,
            Long orderCount,
            Long returnedOrderCount
    ) {
    }

    public record Dataset(
            String sourceProvider,
            String dataReference,
            Long dataVersion,
            OffsetDateTime retrievedAt,
            String currency,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            List<Month> months,
            String creditScoreBand
    ) { public Dataset(String provider,String reference,Long version,OffsetDateTime at,String currency,LocalDate start,LocalDate end,List<Month> months){this(provider,reference,version,at,currency,start,end,months,"NOT_AVAILABLE");}
    }

    public record IdentityVerification(
            String status,
            OffsetDateTime checkedAt
    ) {
    }

    public record FinancialConsent(
            UUID consentId,
            int consentVersion,
            String scope,
            String status
    ) {
    }

    public record FinancialData(
            LocalDate asOfDate,
            int periodMonths,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            String currency,
            List<Month> months,
            String creditScoreBand
    ) { public FinancialData(LocalDate asOf,int period,LocalDate start,LocalDate end,String currency,List<Month> months){this(asOf,period,start,end,currency,months,"NOT_AVAILABLE");}
    }

    public record Response(
            UUID applicationId,
            int applicationRevision,
            UUID verificationId,
            IdentityVerification identityVerification,
            FinancialConsent financialConsent,
            String sourceProvider,
            String dataReference,
            long dataVersion,
            OffsetDateTime retrievedAt,
            FinancialData financialData
    ) {
    }
}