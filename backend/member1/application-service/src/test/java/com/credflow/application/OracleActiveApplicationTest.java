package com.credflow.application;

import com.credflow.application.service.ApplicationService;
import com.credflow.application.dto.*;
import com.credflow.application.exception.ActiveApplicationException;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real Oracle test. Stop the live Application service first so its
 * publisher cannot emit test rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "CREDFLOW_ORACLE_GUARD_TEST", matches = "true")
class OracleActiveApplicationTest {
	@Autowired
	ApplicationService service;
	@Autowired
	JdbcTemplate jdbc;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry r) throws Exception {
		Properties p = new Properties();
		try (var in = Files.newBufferedReader(Path.of(System.getenv("CREDFLOW_TEST_CONFIG")))) {
			p.load(in);
		}
		p.forEach((k, v) -> r.add(k.toString(), () -> v.toString()));
		r.add("server.port", () -> "0");
		r.add("credflow.outbox.enabled", () -> "false");
		r.add("credflow.status-consumer.enabled", () -> "false");
		r.add("spring.kafka.listener.auto-startup", () -> "false");
	}

	@Test
	void oracleSerializesDifferentKeysAndKeepsReplayAndClosedReapplication() throws Exception {
		String seller = UUID.randomUUID().toString(), market = "11111111-1111-4111-8111-111111111111",
				ref = "GUARD-" + UUID.randomUUID();
		Jwt jwt = Jwt.withTokenValue("unit-method-call").header("alg", "RS256").subject("test-marketplace")
				.claim("marketplaceId", market).claim("client_id", "marketplace-submission-" + market)
				.claim("scope", "application.submit").claim("roles", List.of("MARKETPLACE")).build();
		jdbc.update("INSERT INTO CF_SELLER_MAPPINGS (MARKETPLACE_ID,MARKETPLACE_SELLER_ID,SELLER_ID) VALUES (?,?,?)",
				market, ref, seller);
		var pool = Executors.newFixedThreadPool(2);
		var barrier = new CyclicBarrier(2);
		var requests = new ArrayList<ApplicationRequest>();
		var keys = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
		for (int i = 0; i < 2; i++)
			requests.add(new ApplicationRequest(UUID.fromString(market), ref, "GUARD-" + UUID.randomUUID(), "100000.00",
					"INR", LoanPurpose.WORKING_CAPITAL,
					new ApplicationRequest.BusinessProfile("Oracle guard test", "Isolated guard test",
							LocalDate.of(2020, 1, 1)),
					UUID.randomUUID().toString(),
					new ApplicationRequest.InitialSharingConsent("APPLICATION_SHARE_WITH_CREDFLOW", "v1",
							OffsetDateTime.now().minusMinutes(1), UUID.randomUUID().toString())));
		try {
			var futures = new ArrayList<Future<Integer>>();
			for (int i = 0; i < 2; i++) {
				final int index = i;
				futures.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					try {
						service.createApplication(requests.get(index), keys.get(index), jwt);
						return index;
					} catch (ActiveApplicationException ex) {
						return -1;
					}
				}));
			}
			var outcomes = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
			assertEquals(1, outcomes.stream().filter(i -> i >= 0).count());
			assertEquals(1, outcomes.stream().filter(i -> i == -1).count());
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM CF_APPLICATIONS WHERE SELLER_ID=?", Integer.class,
					seller));
			int winner = outcomes.stream().filter(i -> i >= 0).findFirst().orElseThrow();
			var replay = service.createApplication(requests.get(winner), keys.get(winner), jwt);
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM CF_APPLICATIONS WHERE SELLER_ID=?", Integer.class,
					seller));
			jdbc.update("UPDATE CF_APPLICATIONS SET STATUS='CANCELLED' WHERE SELLER_ID=?", seller);
			var next = service.createApplication(requests.get(1 - winner), keys.get(1 - winner), jwt);
			assertNotEquals(replay.applicationId(), next.applicationId());
			assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM CF_APPLICATIONS WHERE SELLER_ID=?", Integer.class,
					seller));
		} finally {
			pool.shutdown();
			assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS));
			jdbc.update(
					"DELETE FROM CF_OUTBOX_EVENTS WHERE AGGREGATE_ID IN (SELECT PUBLIC_APPLICATION_ID FROM CF_APPLICATIONS WHERE SELLER_ID=?)",
					seller);
			jdbc.update(
					"DELETE FROM CF_IDEMPOTENCY_KEYS WHERE PUBLIC_APPLICATION_ID IN (SELECT PUBLIC_APPLICATION_ID FROM CF_APPLICATIONS WHERE SELLER_ID=?)",
					seller);
			jdbc.update("DELETE FROM CF_APPLICATIONS WHERE SELLER_ID=?", seller);
			jdbc.update("DELETE FROM CF_SELLER_MAPPINGS WHERE SELLER_ID=?", seller);
		}
	}
}
