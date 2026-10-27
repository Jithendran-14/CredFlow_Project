package com.credflow.auth.management;
import jakarta.validation.constraints.*;
public record LenderInput(
 @NotBlank @Size(max=150) String name,
 @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{2,19}") String code,
 @NotBlank @Pattern(regexp="BANK|NBFC") String type,
 @NotBlank @Size(min=3,max=100) @Pattern(regexp="[A-Za-z0-9._-]+") String username,
 @NotBlank @Email @Size(max=255) String emailAddress,
 @NotBlank @Size(min=12,max=72) String password,
 @NotBlank @Size(max=450) String reason) {}
