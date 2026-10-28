package com.credflow.auth.management;

import jakarta.validation.constraints.*;

public record MarketplaceUpdate(@NotBlank @Size(max = 150) String name,
		@NotBlank @Email @Size(max = 255) String emailAddress,
		@NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status, @NotNull @PositiveOrZero Long expectedVersion) {
}
