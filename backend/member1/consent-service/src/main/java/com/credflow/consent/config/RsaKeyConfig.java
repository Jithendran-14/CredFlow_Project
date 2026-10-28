package com.credflow.consent.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

import java.util.Base64;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class RsaKeyConfig {

	@Bean
	public JwtDecoder jwtDecoder(@Value("${credflow.jwt.public-key-path}") String publicKeyPath,
			@Value("${credflow.jwt.issuer}") String issuer, @Value("${credflow.jwt.audience}") String audience)
			throws Exception {

		String pem = Files.readString(Path.of(publicKeyPath), StandardCharsets.UTF_8);

		String encodedKey = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
				.replaceAll("\\s", "");

		byte[] keyBytes = Base64.getDecoder().decode(encodedKey);

		RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
				.generatePublic(new X509EncodedKeySpec(keyBytes));

		if (publicKey.getModulus().bitLength() < 2048) {
			throw new IllegalStateException("JWT RSA public key must be at least 2048 bits");
		}

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();

		OAuth2TokenValidator<Jwt> issuerAndTimeValidator = JwtValidators.createDefaultWithIssuer(issuer);

		// 1. Direct validation using the standard Jwt object methods
		OAuth2TokenValidator<Jwt> audienceValidator = token -> {
			List<String> audiences = token.getAudience();
			if (audiences != null && audiences.contains(audience)) {
				return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
			}
			return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
					.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token",
							"The audience is invalid", null));
		};

		// 2. Direct Subject Validation
		OAuth2TokenValidator<Jwt> subjectValidator = token -> {
			String subject = token.getSubject();
			if (subject != null && !subject.isBlank()) {
				return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
			}
			return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
					.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token",
							"The subject is missing or empty", null));
		};

		// 3. Direct Expiry Validation
		OAuth2TokenValidator<Jwt> expiryRequiredValidator = token -> {
			if (token.getExpiresAt() != null) {
				return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
			}
			return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
					.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token",
							"The token expiry is missing", null));
		};

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimeValidator, audienceValidator,
				subjectValidator, expiryRequiredValidator));

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimeValidator, audienceValidator,
				subjectValidator, expiryRequiredValidator));

		return decoder;
	}
}
