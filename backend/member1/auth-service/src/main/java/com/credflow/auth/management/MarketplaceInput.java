package com.credflow.auth.management;
import jakarta.validation.constraints.*;
public record MarketplaceInput(
 @NotBlank @Size(max=150) String name,
 @NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9]{1,7}") String referencePrefix,
 @NotBlank @Size(min=3,max=100) @Pattern(regexp="[A-Za-z0-9._-]+") String username,
 @NotBlank @Email @Size(max=255) String emailAddress,
 @NotBlank @Size(min=12,max=72) String password) {}
