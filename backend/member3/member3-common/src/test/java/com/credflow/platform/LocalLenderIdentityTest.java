package com.credflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LocalLenderIdentityTest {
	private static final String BANK_A = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1";
	private static final String NBFC_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2";

	private MockEnvironment localEnvironment() {
		var env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
		env.setActiveProfiles("local-api-test");
		return env;
	}

	private Platform platform(MockEnvironment env) {
		return new Platform(env, new ObjectMapper(), mock(PlatformTransactionManager.class));
	}

	@Test
	void bankAOfficerCanReviewOnlyItsOwnLender() {
		var env = localEnvironment();
		Jwt jwt = new PlatformSecurity().decoder(env).decode("local-lender-bank-a");
		var platform = platform(env);
		assertEquals("lender-officer-bank-a", jwt.getSubject());
		assertEquals(BANK_A, platform.lenderId(jwt));
		assertTrue(platform.hasRole(jwt, "LENDER_OFFICER"));
		assertFalse(platform.hasRole(jwt, "ADMIN"));
		assertFalse(platform.hasRole(jwt, "SELLER"));
		assertDoesNotThrow(() -> platform.requireScope(jwt, "lender.review"));
		assertDoesNotThrow(() -> platform.owner(jwt, null, null, BANK_A, null, true));
		assertThrows(ResponseStatusException.class, () -> platform.owner(jwt, null, null, NBFC_B, null, true));
		assertThrows(ResponseStatusException.class,
				() -> platform.machine(jwt, "integration.write", "orchestration-service"));
	}

	@Test
	void nbfcOfficerCannotReadBankAResources() {
		var env = localEnvironment();
		Jwt jwt = new PlatformSecurity().decoder(env).decode("local-lender-nbfc-b");
		var platform = platform(env);
		assertEquals(NBFC_B, platform.lenderId(jwt));
		assertTrue(platform.hasRole(jwt, "LENDER_OFFICER"));
		assertDoesNotThrow(() -> platform.requireScope(jwt, "lender.review"));
		assertThrows(ResponseStatusException.class, () -> platform.owner(jwt, null, null, BANK_A, null, true));
	}

	@Test
	void usesConfiguredLenderIdentifiers() {
		var env = localEnvironment().withProperty("credflow.lenders.bank-a-id", "configured-bank")
				.withProperty("credflow.lenders.nbfc-b-id", "configured-nbfc");
		var decoder = new PlatformSecurity().decoder(env);
		assertEquals("configured-bank", decoder.decode("local-lender-bank-a").getClaimAsString("lenderId"));
		assertEquals("configured-nbfc", decoder.decode("local-lender-nbfc-b").getClaimAsString("lenderId"));
	}

	@Test
	void localProfileRequiresExplicitLoopbackBinding() {
		for (String address : new String[] { "0.0.0.0", "localhost", "192.168.1.2", "" }) {
			var env = localEnvironment().withProperty("server.address", address);
			assertThrows(IllegalStateException.class, () -> new PlatformSecurity().decoder(env));
		}
	}

	@Test
	void unknownIdentityIsRejectedAndExistingSellerStillWorks() {
		var env = localEnvironment();
		var decoder = new PlatformSecurity().decoder(env);
		assertThrows(JwtException.class, () -> decoder.decode("local-admin"));
		var seller = decoder.decode("local-seller");
		assertTrue(platform(env).hasRole(seller, "SELLER"));
		assertFalse(platform(env).hasRole(seller, "LENDER_OFFICER"));
		assertEquals("SELLER-1001", platform(env).sellerId(seller));
	}

	@Test
	void normalProfileStillRequiresConfiguredJwtVerification() {
		var env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
		assertThrows(IllegalStateException.class, () -> new PlatformSecurity().decoder(env));
	}
}
