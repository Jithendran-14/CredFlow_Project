package com.credflow.application.dto;
import java.time.*;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
public record Phase0Details(
 @NotNull @Min(1) @Max(120) Integer preferredTenureMonths,
 @NotBlank @Pattern(regexp="[0-9]{1,12}\\.[0-9]{2}") String declaredMonthlyIncome,
 @NotBlank @Pattern(regexp="[0-9]{1,12}\\.[0-9]{2}") String monthlyExpenses,
 @NotBlank @Pattern(regexp="[0-9]{1,12}\\.[0-9]{2}") String existingMonthlyDebt,
 @NotNull @Past LocalDate dateOfBirth,
 @NotBlank @Pattern(regexp="MOCK_ID") String documentType,
 @NotBlank @Pattern(regexp="TEST-[A-Z0-9-]{6,40}") String documentReference,
 @NotNull @AssertTrue Boolean detailsConfirmed,
 @NotNull @AssertTrue Boolean kycTermsAccepted) {
 public void validate(){
  if(preferredTenureMonths==null||preferredTenureMonths<1||preferredTenureMonths>120||dateOfBirth==null||dateOfBirth.isAfter(LocalDate.now(ZoneOffset.UTC).minusYears(18))||dateOfBirth.isBefore(LocalDate.now(ZoneOffset.UTC).minusYears(120))||!Boolean.TRUE.equals(detailsConfirmed)||!Boolean.TRUE.equals(kycTermsAccepted)||!"MOCK_ID".equals(documentType)||documentReference==null||!documentReference.matches("TEST-[A-Z0-9-]{6,40}"))throw new IllegalArgumentException("Complete prototype KYC: seller must be at least 18, confirm correct details and accept KYC terms. Use a fictional TEST document reference.");
  for(String value:new String[]{declaredMonthlyIncome,monthlyExpenses,existingMonthlyDebt})if(value==null||!value.matches("[0-9]{1,12}\\.[0-9]{2}"))throw new IllegalArgumentException("Income, expenses and existing debt must be non-negative amounts with two decimal places.");
  if(new BigDecimal(declaredMonthlyIncome).signum()<=0)throw new IllegalArgumentException("Declared monthly income must be positive.");
 }
}
