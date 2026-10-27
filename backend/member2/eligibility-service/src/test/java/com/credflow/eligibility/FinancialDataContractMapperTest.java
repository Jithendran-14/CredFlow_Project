package com.credflow.eligibility;

import com.credflow.eligibility.integration.FinancialDataContractMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class FinancialDataContractMapperTest {
    final ObjectNode sample=Member1FinancialFixture.sample();
    final FinancialDataContractMapper.Expected expected=Member1FinancialFixture.expected(sample);
    FinancialDataContractMapper.Mapped map() {return Member1FinancialFixture.MAPPER.map(sample.toString(),expected);}
    void invalid() {assertThatThrownBy(this::map).isInstanceOf(FinancialDataContractMapper.FinancialContractException.class);}
    @Test void exactTeammateExampleKeepsRealZeroSeparateFromMissing() {
        var result=map();var months=result.financialData().months();
        assertThat(months).hasSize(3);
        assertThat(months.get(0).grossSales()).isEqualByComparingTo("450000.00");
        assertThat(months.get(1).coverage()).isEqualTo(FinancialImportService.Coverage.COMPLETE);
        assertThat(months.get(1).grossSales()).isEqualByComparingTo("0.00");assertThat(months.get(1).orderCount()).isZero();
        assertThat(months.get(2).coverage()).isEqualTo(FinancialImportService.Coverage.INCOMPLETE);
        assertThat(months.get(2).grossSales()).isNull();assertThat(months.get(2).returnedOrderCount()).isNull();
        assertThat(result.financialData().dataReference()).isEqualTo("FIN-DATA-0001");
        assertThat(result.financialData().dataVersion()).isEqualTo(1);
        assertThat(result.verificationId()).isEqualTo(expected.verificationId());
        assertThat(result.consentEvidence().consentId()).isEqualTo("CONSENT-DEMO-001");
    }
    @Test void reorderedMonthsAreSorted() {
        var rows=(ArrayNode)sample.path("financialData").path("monthlyMetrics");var first=rows.remove(0);rows.add(first);
        assertThat(map().financialData().months()).extracting(m->m.month().toString()).containsExactly("2026-07","2026-08","2026-09");
    }
    @ParameterizedTest @ValueSource(strings={"applicationId","verificationId","sellerId","applicationRevision"})
    void mismatchedIdentityOrRevisionRejected(String name) {
        if(name.equals("applicationRevision"))sample.put(name,2);
        else sample.put(name,name.equals("sellerId")?"SELLER-2002":UUID.randomUUID().toString());
        assertThatThrownBy(this::map).hasMessageContaining("does not match");
    }
    @Test void textVerificationIdRejected() {sample.put("verificationId","VERIFICATION-DEMO-001");invalid();}
    @ParameterizedTest @ValueSource(strings={"applicationRevision","financialDataVersion"})
    void fractionalVersionsRejected(String field) {sample.put(field,1.5);invalid();}
    @Test void zeroDataVersionRejected() {sample.put("financialDataVersion",0);invalid();}
    @Test void absentMonthMustNotBeInventedAsZero() {
        ((ArrayNode)sample.path("financialData").path("monthlyMetrics")).remove(2);invalid();
    }
    @Test void duplicateMonthRejected() {Member1FinancialFixture.month(sample,1).put("month","2026-07");invalid();}
    @Test void currentMonthRejected() {Member1FinancialFixture.month(sample,2).put("month","2026-10");invalid();}
    @Test void missingMonthWithNumericMetricsRejected() {Member1FinancialFixture.month(sample,2).put("sales","0.00");invalid();}
    @Test void missingMonthWithAbsentMetricRejected() {Member1FinancialFixture.month(sample,2).remove("returnedOrderCount");invalid();}
    @Test void availableMonthWithoutReturnedCountRejected() {Member1FinancialFixture.month(sample,0).putNull("returnedOrderCount");invalid();}
    @Test void unknownCoverageRejected() {Member1FinancialFixture.month(sample,0).put("dataStatus","UNKNOWN");invalid();}
    @Test void numericMoneyRejectedRatherThanCoerced() {Member1FinancialFixture.month(sample,0).put("sales",450000);invalid();}
    @Test void fractionalOrderCountRejectedRatherThanTruncated() {Member1FinancialFixture.month(sample,0).put("orderCount",2.5);invalid();}
    @Test void negativeMoneyRejected() {Member1FinancialFixture.month(sample,0).put("returns","-1.00");invalid();}
    @Test void excessivePrecisionIsNotRounded() {Member1FinancialFixture.month(sample,0).put("sales","450000.001");invalid();}
    @Test void countOutsideStorageRangeRejected() {Member1FinancialFixture.month(sample,0).put("orderCount",10_000_000_000L);invalid();}
    @ParameterizedTest @ValueSource(strings={"REVOKED","EXPIRED","DECLINED"})
    void inactiveConsentRejected(String status) {((ObjectNode)sample.path("consent")).put("status",status);invalid();}
    @Test void expiredConsentRejected() {((ObjectNode)sample.path("consent")).put("expiresAt",EligibilityTestContext.NOW.toString());invalid();}
    @Test void sharingConsentIsNotFinancialConsent() {((ObjectNode)sample.path("consent")).put("scope","APPLICATION_SHARE_WITH_CREDFLOW");invalid();}
    @Test void futureRetrievalRejected() {((ObjectNode)sample.path("source")).put("retrievedAt",EligibilityTestContext.NOW.plusSeconds(1).toString());invalid();}
    @Test void retrievalBeforeConsentRejected() {((ObjectNode)sample.path("source")).put("retrievedAt","2026-10-14T00:00:00Z");invalid();}
    @Test void timezoneOffsetAcceptedAsSameInstant() {
        ((ObjectNode)sample.path("source")).put("retrievedAt","2026-10-15T14:35:00+05:30");
        assertThat(map().financialData().retrievedAt()).isEqualTo(Instant.parse("2026-10-15T09:05:00Z"));
    }
    @Test void wrongPeriodOrCurrentMonthFlagRejected() {((ObjectNode)sample.path("financialData")).put("excludesCurrentMonth",false);invalid();}
    @Test void malformedJsonHasSafeError() {
        assertThatThrownBy(()->Member1FinancialFixture.MAPPER.map("not-json SECRET",expected))
            .hasMessage("Financial response must be one valid JSON object");
    }
    @Test void trailingJsonRejected() {
        assertThatThrownBy(()->Member1FinancialFixture.MAPPER.map(sample+" {}",expected)).isInstanceOf(FinancialDataContractMapper.FinancialContractException.class);
    }
    @Test void duplicateIdentityFieldRejected() {
        String value=sample.toString().replaceFirst("\\{","{\"applicationRevision\":2,");
        assertThatThrownBy(()->Member1FinancialFixture.MAPPER.map(value,expected)).isInstanceOf(FinancialDataContractMapper.FinancialContractException.class);
    }
    @Test void inconsistentNumbersArePreservedForDomainActionRequired() {
        Member1FinancialFixture.month(sample,0).put("returns","500000.00");
        assertThat(map().financialData().months().getFirst().returnsAmount()).isEqualByComparingTo("500000.00");
    }
}
