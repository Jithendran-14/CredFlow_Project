package com.credflow.platform;

import com.credflow.platform.entity.DeliveryRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlatformTest {

	private final Platform p = new Platform(
			new MockEnvironment().withProperty("credflow.security.role-mode", "KEYCLOAK"),
			new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class));

	private Jwt user(String subject, String lender, String role, String scope) {
		var builder = Jwt.withTokenValue("test").header("alg", "RS256").subject(subject).issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60))
				.claim("resource_access", Map.of("credflow-ui", Map.of("roles", List.of(role)))).claim("scope", scope)
				.claim("azp", "credflow-ui");
		if (lender != null) {
			builder.claim("lender_id", lender);
		}
		return builder.build();
	}

	@Test
	void anotherSellerCannotReadTheApplication() {
		Jwt jwt = user("seller-B", null, "SELLER", "");
		assertThrows(ResponseStatusException.class, () -> p.owner(jwt, "seller-A", null, true));
		assertDoesNotThrow(() -> p.owner(jwt, "seller-B", null, false));
	}

	@Test
	void anOfficerCannotActForAnotherLender() {
		Jwt jwt = user("officer", "lender-A", "LENDER_OFFICER", "lender.review");
		assertThrows(ResponseStatusException.class, () -> p.owner(jwt, null, "lender-B", false));
		assertDoesNotThrow(() -> p.owner(jwt, null, "lender-A", false));
	}

	@Test
	void scopeAloneDoesNotImpersonateASystemClient() {
		Jwt jwt = user("seller", null, "SELLER", "integration.write");
		assertThrows(ResponseStatusException.class, () -> p.machine(jwt, "integration.write", "orchestration-service"));
	}

	@Test
	void operationsRoleWithoutMarketplaceIdentityCannotReadBusinessData() {
		Jwt jwt = user("operator", null, "MARKETPLACE_OPERATIONS", "");
		assertThrows(ResponseStatusException.class, () -> p.owner(jwt, "seller", null, true));
		assertThrows(ResponseStatusException.class, () -> p.owner(jwt, "seller", null, false));
	}

	@Test
	void operationsAccessIsLimitedToItsMarketplaceAndReadOnly() {
		UUID ownMarketplace = UUID.randomUUID();
		Jwt operator = Jwt.withTokenValue("test").header("alg", "RS256").subject("operator")
				.claim("marketplace_id", ownMarketplace.toString())
				.claim("resource_access", Map.of("credflow-ui", Map.of("roles", List.of("MARKETPLACE_OPERATIONS"))))
				.build();
		assertDoesNotThrow(() -> p.owner(operator, "seller", null, ownMarketplace, true));
		var otherMarketplace = assertThrows(ResponseStatusException.class,
				() -> p.owner(operator, "seller", null, UUID.randomUUID(), true));
		assertEquals(404, otherMarketplace.getStatusCode().value());
		assertEquals("RESOURCE_NOT_FOUND", otherMarketplace.getReason());
		assertThrows(ResponseStatusException.class, () -> p.owner(operator, "seller", null, ownMarketplace, false));
		assertThrows(ResponseStatusException.class, () -> p.owner(operator, "seller", null, true));
	}

	@Test
	void administratorAccessRemainsReadOnly() {
		Jwt administrator = user("admin", null, "ADMIN", "");
		assertDoesNotThrow(() -> p.owner(administrator, "seller", null, UUID.randomUUID(), true));
		assertThrows(ResponseStatusException.class,
				() -> p.owner(administrator, "seller", null, UUID.randomUUID(), false));
	}

	@Test
	void objectFieldOrderDoesNotChangeIdempotencyHash() throws Exception {
		var mapper = new ObjectMapper();
		assertEquals(p.hash(mapper.readTree("{\"a\":1,\"b\":2}")), p.hash(mapper.readTree("{\"b\":2,\"a\":1}")));
		assertNotEquals(p.hash(Map.of("amount", "10.00")), p.hash(Map.of("amount", "11.00")));
	}

	@Test
	void oracleDeliveryMappingsAndQueryParse() {
		var config = new org.hibernate.cfg.Configuration();
		config.setProperty("hibernate.dialect", "org.hibernate.dialect.OracleDialect");
		config.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
		config.setProperty("hibernate.hbm2ddl.auto", "none");
		config.addAnnotatedClass(DeliveryRow.class);
		try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
			em.createQuery("select r.id from DeliveryRow r where " + "((r.status='PENDING' and r.nextAttempt<=:now) "
					+ "or (r.status='RUNNING' and r.leaseUntil<:now)) "
					+ "and (r.kind in ('INBOX','JOB') or not exists " + "(select x.id from DeliveryRow x where "
					+ "x.applicationId=r.applicationId " + "and x.kind=r.kind and x.position<r.position "
					+ "and x.status<>'DONE')) " + "order by r.position", String.class);
		}
	}
}
