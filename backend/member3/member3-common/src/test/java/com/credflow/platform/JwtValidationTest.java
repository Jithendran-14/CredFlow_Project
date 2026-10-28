package com.credflow.platform;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses a local JWKS endpoint and real RSA signatures; no external identity
 * service.
 */
class JwtValidationTest {

	private HttpServer server;

	private KeyPair pair;

	private String issuer;

	private JwtDecoder decoder;

	@BeforeEach
	void startJwks() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		pair = generator.generateKeyPair();
		var publicKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("credflow-test").build();
		byte[] body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/explicit-public-jwks", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
		decoder = new PlatformSecurity()
				.decoder(environment().withProperty("credflow.security.jwk-set-uri", jwksUri()));
	}

	private MockEnvironment environment() {
		return new MockEnvironment().withProperty("credflow.security.issuer", issuer)
				.withProperty("credflow.security.audience", "orchestration-service");
	}

	private String jwksUri() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/explicit-public-jwks";
	}

	@AfterEach
	void stopJwks() {
		if (server != null)
			server.stop(0);
	}

	private String token(KeyPair signingKey, String tokenIssuer, String audience, Instant expires)
			throws JOSEException {
		var claims = new JWTClaimsSet.Builder().subject("seller-123").issuer(tokenIssuer).audience(audience)
				.issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expires)).build();
		var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("credflow-test").build(), claims);
		jwt.sign(new RSASSASigner(signingKey.getPrivate()));
		return jwt.serialize();
	}

	@Test
	void acceptsValidSignatureIssuerAudienceAndExpiry() throws Exception {
		assertEquals("seller-123", decoder
				.decode(token(pair, issuer, "orchestration-service", Instant.now().plusSeconds(60))).getSubject());
	}

	@Test
	void productionDecoderRejectsLocalLenderMarkers() {
		assertThrows(JwtException.class, () -> decoder.decode("local-lender-bank-a"));
		assertThrows(JwtException.class, () -> decoder.decode("local-lender-nbfc-b"));
	}

	@Test
	void rejectsAnotherServiceAudience() throws Exception {
		String jwt = token(pair, issuer, "audit-service", Instant.now().plusSeconds(60));
		assertThrows(JwtException.class, () -> decoder.decode(jwt));
	}

	@Test
	void rejectsAnotherIssuer() throws Exception {
		String jwt = token(pair, issuer + "-other", "orchestration-service", Instant.now().plusSeconds(60));
		assertThrows(JwtException.class, () -> decoder.decode(jwt));
	}

	@Test
	void rejectsExpiredCredentials() throws Exception {
		String jwt = token(pair, issuer, "orchestration-service", Instant.now().minusSeconds(120));
		assertThrows(JwtException.class, () -> decoder.decode(jwt));
	}

	@Test
	void rejectsAnUntrustedSignature() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String jwt = token(generator.generateKeyPair(), issuer, "orchestration-service", Instant.now().plusSeconds(60));
		assertThrows(JwtException.class, () -> decoder.decode(jwt));
	}

	@Test
	void rejectsMissingSubjectIdentity() throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("orchestration-service")
				.expirationTime(Date.from(Instant.now().plusSeconds(60))).build();
		var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("credflow-test").build(), claims);
		jwt.sign(new RSASSASigner(pair.getPrivate()));
		assertThrows(JwtException.class, () -> decoder.decode(jwt.serialize()));
	}

	@Test
	void publicPemKeyValidatesRs256WithoutAnyAuthNetworkEndpoint(@org.junit.jupiter.api.io.TempDir Path folder)
			throws Exception {
		Path keyFile = folder.resolve("auth-public.pem");
		String pem = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(pair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n";
		Files.writeString(keyFile, pem);
		JwtDecoder pemDecoder = new PlatformSecurity().decoder(
				environment().withProperty("credflow.security.public-key-location", keyFile.toUri().toString()));
		assertEquals("seller-123", pemDecoder
				.decode(token(pair, issuer, "orchestration-service", Instant.now().plusSeconds(60))).getSubject());
		var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).build(),
				new JWTClaimsSet.Builder().subject("seller-123").issuer(issuer).audience("orchestration-service")
						.expirationTime(Date.from(Instant.now().plusSeconds(60))).build());
		signed.sign(new RSASSASigner(pair.getPrivate()));
		assertThrows(JwtException.class, () -> pemDecoder.decode(signed.serialize()));
	}

	@Test
	void rejectsCredentialsWithoutExpiry() throws Exception {
		var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("credflow-test").build(),
				new JWTClaimsSet.Builder().subject("seller").issuer(issuer).audience("orchestration-service").build());
		jwt.sign(new RSASSASigner(pair.getPrivate()));
		assertThrows(JwtException.class, () -> decoder.decode(jwt.serialize()));
	}

	@Test
	void missingOrAmbiguousVerificationKeyFailsStartup() {
		var config = new PlatformSecurity();
		assertThrows(IllegalStateException.class, () -> config.decoder(environment()));
		assertThrows(IllegalStateException.class,
				() -> config.decoder(environment().withProperty("credflow.security.jwk-set-uri", jwksUri())
						.withProperty("credflow.security.public-key-location", "classpath:auth.pem")));
	}

	@Test
	void missingIssuerAudienceAndUnsupportedRoleModeFailStartup() {
		var config = new PlatformSecurity();
		assertThrows(IllegalStateException.class,
				() -> config.decoder(
						new MockEnvironment().withProperty("credflow.security.audience", "orchestration-service")
								.withProperty("credflow.security.jwk-set-uri", jwksUri())));
		assertThrows(IllegalStateException.class,
				() -> config.decoder(new MockEnvironment().withProperty("credflow.security.issuer", issuer)
						.withProperty("credflow.security.jwk-set-uri", jwksUri())));
		assertThrows(IllegalStateException.class,
				() -> config.decoder(environment().withProperty("credflow.security.jwk-set-uri", jwksUri())
						.withProperty("credflow.security.role-mode", "UNKNOWN")));
	}

	@Test
	void unreadableOrUntrustedResourceLocationFailsStartup() {
		var config = new PlatformSecurity();
		assertThrows(IllegalStateException.class, () -> config.decoder(environment()
				.withProperty("credflow.security.public-key-location", "classpath:missing-auth-public.pem")));
		assertThrows(IllegalStateException.class, () -> config.decoder(environment()
				.withProperty("credflow.security.public-key-location", "https://unconfirmed.example/key.pem")));
	}
}
