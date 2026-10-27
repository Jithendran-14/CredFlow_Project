package com.credflow.eligibility.api;
import jakarta.validation.constraints.*;import java.util.Set;
public record LenderRulesInput(@NotNull @Positive Integer expectedVersion,@NotBlank @Size(max=100)String productName,@NotNull @Min(0) @Max(1200)Integer minAgeMonths,
 @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?")String minAverageNet,
 @NotBlank @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,4})?")String maxReturnPercent,
 @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?")String minAmount,
 @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?")String maxAmount,
 @NotBlank @Pattern(regexp="[0-9]{1,7}(\\.[0-9]{1,4})?")String salesMultiplier,
 @Min(0) @Max(99999)Integer routingPriority,@NotNull Boolean active,
 @NotEmpty @Size(max=3) Set<@NotBlank @Pattern(regexp="PURCHASE_INVENTORY|WORKING_CAPITAL|BUSINESS_EXPANSION")String> purposes,@NotBlank @Size(max=500)String reason,
 @NotBlank @Pattern(regexp="POOR|FAIR|GOOD|EXCELLENT") String minCreditScoreBand,
 @NotBlank @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,4})?") String maxRepaymentPercent,
 @NotBlank @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,4})?") String assessmentAprPercent){
 public LenderRulesInput(Integer version,String name,Integer age,String net,String returns,String min,String max,String multiplier,Integer priority,Boolean active,Set<String> purposes,String reason){this(version,name,age,net,returns,min,max,multiplier,priority,active,purposes,reason,"FAIR","40","18");}
}
