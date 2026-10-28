
package com.credflow.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, String userId, String username,
		String role, String sellerId, String marketplaceId) {
}
