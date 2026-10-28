package com.credflow.auth.dto;

public record OAuthTokenResponse(String access_token, String token_type, long expires_in, String scope) {
}