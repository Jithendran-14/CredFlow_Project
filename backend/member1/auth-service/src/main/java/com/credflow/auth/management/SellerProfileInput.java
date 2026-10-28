package com.credflow.auth.management;

import jakarta.validation.constraints.*;

public record SellerProfileInput(@NotBlank @Size(max = 100) String fullName,
		@NotBlank @Email @Size(max = 255) String emailAddress,
		@NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String phone, @NotBlank @Size(max = 500) String address,
		@NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 100) String state,
		@NotBlank @Pattern(regexp = "[1-9][0-9]{5}") String postalCode, @NotNull @PositiveOrZero Long expectedVersion) {
}
