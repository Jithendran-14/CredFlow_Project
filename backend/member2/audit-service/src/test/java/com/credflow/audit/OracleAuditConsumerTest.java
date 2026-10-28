package com.credflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ORACLE_AUDIT_TESTS", matches = "true")
class OracleAuditConsumerTest {
	static JdbcTemplate jdbc;
	static TransactionTemplate tx;
	static ApplicationAuditConsumer consumer;
	static final ObjectMapper json = new ObjectMapper();

	@BeforeAll
	static void setup() {
		String password = System.getenv("CF_AUDIT_PASSWORD");
		if (password == null)
			throw new IllegalStateException("Load local environment first");
		var ds = new DriverManagerDataSource(
				System.getenv().getOrDefault("ORACLE_JDBC_URL", "jdbc:oracle:thin:@localhost:1521/FREEPDB1"),
				"CF_AUDIT", password);
		jdbc = new JdbcTemplate(ds);
		tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
		consumer = new ApplicationAuditConsumer(new JdbcTestAccess(jdbc), json);
	}

	void consume(String payload) {
		tx.executeWithoutResult(status -> {
			try {
				consumer.consume(payload);
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	int count(String table, String id) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_id=?", Integer.class, id);
	}

	void cleanup(String id) {
		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM audit_event WHERE event_id=?", id);
			jdbc.update("DELETE FROM event_inbox WHERE event_id=?", id);
		});
	}

	@Test
	void realOracleStoresOneSafeHistoryRecordForRepeatedDelivery() throws Exception {
		var e = AuditTestMessages.event(json);
		e.put("eventId", java.util.UUID.randomUUID().toString());
		e.put("password", "TOP_SECRET");
		((com.fasterxml.jackson.databind.node.ObjectNode) e.path("actor")).put("accessToken", "TOP_SECRET");
		String id = e.path("eventId").asText(), payload = json.writeValueAsString(e);
		try {
			consume(payload);
			consume(payload);
			assertThat(count("audit_event", id)).isEqualTo(1);
			assertThat(count("event_inbox", id)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT safe_payload FROM audit_event WHERE event_id=?", String.class, id))
					.doesNotContain("TOP_SECRET", "password", "accessToken");
			System.out.println(
					"ORACLE AUDIT CHECK: repeated delivery stored one safe history record and one inbox marker");
		} finally {
			cleanup(id);
		}
	}

	@Test
	void failedHistoryInsertRollsBackTheInboxClaim() throws Exception {
		String payload = AuditTestMessages.unique(json), id = json.readTree(payload).path("eventId").asText();
		var brokenJdbc = new JdbcTemplate(jdbc.getDataSource()) {
			@Override
			public int update(String sql, Object... args) {
				if (sql.startsWith("INSERT INTO audit_event"))
					throw new DataIntegrityViolationException("Simulated history write failure");
				return super.update(sql, args);
			}
		};
		var broken = new ApplicationAuditConsumer(new JdbcTestAccess(brokenJdbc), json);
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			try {
				broken.consume(payload);
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		})).hasCauseInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("audit_event", id)).isZero();
		assertThat(count("event_inbox", id)).isZero();
	}

	@Test
	void concurrentDeliveriesCommitOnlyOneRecord() throws Exception {
		String payload = AuditTestMessages.unique(json), id = json.readTree(payload).path("eventId").asText();
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			Callable<Void> task = () -> {
				start.await();
				consume(payload);
				return null;
			};
			var a = pool.submit(task);
			var b = pool.submit(task);
			start.countDown();
			a.get(20, TimeUnit.SECONDS);
			b.get(20, TimeUnit.SECONDS);
			assertThat(count("audit_event", id)).isEqualTo(1);
			assertThat(count("event_inbox", id)).isEqualTo(1);
		} finally {
			pool.shutdownNow();
			pool.awaitTermination(25, TimeUnit.SECONDS);
			cleanup(id);
		}
	}

	@Test
	void malformedActorCreatesNoRows() throws Exception {
		var e = AuditTestMessages.event(json);
		e.put("eventId", java.util.UUID.randomUUID().toString());
		e.put("actor", "not-an-object");
		String id = e.path("eventId").asText();
		assertThatThrownBy(() -> consume(json.writeValueAsString(e)))
				.hasCauseInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
		assertThat(count("audit_event", id)).isZero();
		assertThat(count("event_inbox", id)).isZero();
	}
}
