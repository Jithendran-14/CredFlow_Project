package com.credflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,

		@NotBlank @Email @Size(max = 255) String emailAddress,

		@NotBlank @Size(min = 12, max = 128) String password,

		@NotBlank String role,

		@Size(max = 100) String sellerId,

		@Size(max = 100) String marketplaceId, @Size(max = 100) String lenderId) {
	public CreateUserRequest(String username, String emailAddress, String password, String role, String sellerId,
			String marketplaceId) {
		this(username, emailAddress, password, role, sellerId, marketplaceId, null);
	}
}