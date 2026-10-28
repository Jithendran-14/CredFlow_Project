package com.credflow.auth.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.*;

public record SellerRegistrationRequest(
		@NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
		@NotBlank @Email @Size(max = 255) String emailAddress, @NotBlank @Size(min = 12, max = 72) String password,
		@NotBlank @Size(max = 36) String marketplaceId, @NotBlank @Size(max = 100) String fullName,
		@NotBlank @Pattern(regexp = "^[6-9][0-9]{9}$") String phone, @NotBlank @Size(max = 200) String businessName,
		@NotNull @PastOrPresent LocalDate businessStartDate, @NotBlank @Size(max = 500) String address,
		@NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 100) String state,
		@NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$") String postalCode) {
}
