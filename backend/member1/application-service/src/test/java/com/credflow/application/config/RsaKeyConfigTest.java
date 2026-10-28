
package com.credflow.application.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

class RsaKeyConfigTest {

	private static final String EXPECTED_ISSUER = "credflow-auth";

	private static final String EXPECTED_AUDIENCE = "credflow-api";

	@TempDir
	Path tempDirectory;

	private KeyPair signingKeyPair;
	private JwtDecoder decoder;

	@BeforeEach
	void setUp() throws Exception {

		// Generate a new test-only RSA key pair.
		signingKeyPair = generateKeyPair();

		// Write the test public key as a PEM file.
		Path publicKeyPath = tempDirectory.resolve("test-public.pem");

		writePublicKeyPem(publicKeyPath, (RSAPublicKey) signingKeyPair.getPublic());

		// Use the real application JWT decoder configuration.
		RsaKeyConfig config = new RsaKeyConfig();

		decoder = config.jwtDecoder(publicKeyPath.toString(), EXPECTED_ISSUER, EXPECTED_AUDIENCE);
	}

	@Test
	void shouldAcceptValidJwt() {

		String token = createToken(signingKeyPair, EXPECTED_ISSUER, EXPECTED_AUDIENCE, Instant.now().plusSeconds(900));

		assertDoesNotThrow(() -> decoder.decode(token));
	}

	@Test
	void shouldRejectIncorrectIssuer() {

		String token = createToken(signingKeyPair, "unknown-auth-service", EXPECTED_AUDIENCE,
				Instant.now().plusSeconds(900));

		assertThrows(JwtException.class, () -> decoder.decode(token));
	}

	@Test
	void shouldRejectIncorrectAudience() {

		String token = createToken(signingKeyPair, EXPECTED_ISSUER, "another-api", Instant.now().plusSeconds(900));

		assertThrows(JwtException.class, () -> decoder.decode(token));
	}

	@Test
	void shouldRejectExpiredJwt() {

		// Expired 5 minutes ago, beyond normal clock tolerance.
		String token = createToken(signingKeyPair, EXPECTED_ISSUER, EXPECTED_AUDIENCE, Instant.now().minusSeconds(300));

		assertThrows(JwtException.class, () -> decoder.decode(token));
	}

	@Test
	void shouldRejectJwtSignedWithAnotherKey() throws Exception {

		// A different RSA key pair represents an untrusted issuer.
		KeyPair untrustedKeyPair = generateKeyPair();

		String token = createToken(untrustedKeyPair, EXPECTED_ISSUER, EXPECTED_AUDIENCE,
				Instant.now().plusSeconds(900));

		assertThrows(JwtException.class, () -> decoder.decode(token));
	}

	private KeyPair generateKeyPair() throws Exception {

		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");

		generator.initialize(2048);

		return generator.generateKeyPair();
	}

	private void writePublicKeyPem(Path path, RSAPublicKey publicKey) throws Exception {

		String encoded = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(publicKey.getEncoded());

		String pem = "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";

		Files.writeString(path, pem);
	}

	private String createToken(KeyPair keyPair, String issuer, String audience, Instant expiresAt) {

		RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

		RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

		RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-rsa-key").build();

		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));

		Instant issuedAt = Instant.now().minusSeconds(600);

		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject("test-user")
				.issuedAt(issuedAt).expiresAt(expiresAt).claim("roles", List.of("SELLER"))
				.claim("sellerId", "SELLER-1001").build();

		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-rsa-key").build();

		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
