package com.credflow.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "ORACLE_FINANCIAL_TESTS", matches = "true")
class OracleEligibilityCompletionTest {
	static JdbcTemplate jdbc;
	static EligibilityTrustedPipeline pipeline;
	static EligibilityCompletionOutbox outbox;
	static final ObjectMapper json = new ObjectMapper();

	@BeforeAll
	static void setup() {
		OracleFinancialImportTest.setup();
		jdbc = OracleFinancialSnapshotTest.jdbc;
		outbox = new EligibilityCompletionOutbox(new JdbcTestAccess(jdbc), json);
		pipeline = new EligibilityTrustedPipeline(
				new EligibilityInputGuard(Clock.fixed(EligibilityTestContext.NOW, ZoneOffset.UTC)),
				OracleFinancialImportTest.pipeline, outbox);
	}

	static void cleanup(UUID app) {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			String id = app.toString();
			jdbc.update(
					"DELETE FROM eligibility_completion WHERE eligibility_result_id IN (SELECT eligibility_result_id FROM eligibility_evaluation WHERE application_id=?)",
					id);
			jdbc.update("DELETE FROM event_outbox WHERE application_id=?", id);
			jdbc.update(
					"DELETE FROM eligibility_lender_result WHERE eligibility_result_id IN (SELECT eligibility_result_id FROM eligibility_evaluation WHERE application_id=?)",
					id);
			jdbc.update("DELETE FROM eligibility_evaluation WHERE application_id=?", id);
			jdbc.update(
					"DELETE FROM financial_snapshot_month WHERE snapshot_id IN (SELECT snapshot_id FROM financial_snapshot WHERE application_id=?)",
					id);
			jdbc.update("DELETE FROM financial_snapshot WHERE application_id=?", id);
			jdbc.update(
					"DELETE FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?)",
					id);
			jdbc.update("DELETE FROM financial_import_batch WHERE application_id=?", id);
		});
	}

	int count(String table, UUID app) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE application_id=?", Integer.class,
				app.toString());
	}

	@Test
	void coherentResultAndOneStableEventAreSavedTogether() {
		var request = EligibilityTestContext.request();
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			var first = pipeline.evaluate(request, EligibilityTestContext.context(request),
					EligibilityTestContext.metadata());
			var second = pipeline.evaluate(request, EligibilityTestContext.context(request),
					EligibilityTestContext.metadata());
			assertThat(second.eventId()).isEqualTo(first.eventId());
			assertThat(count("event_outbox", request.applicationId())).isEqualTo(1);
			try {
				var payload = json.readTree(jdbc.queryForObject("SELECT payload FROM event_outbox WHERE event_id=?",
						String.class, first.eventId().toString()));
				assertThat(payload.path("applicationId").asText()).isEqualTo(request.applicationId().toString());
				assertThat(payload.path("data").path("eligibilityResultId").asText())
						.isEqualTo(first.result().eligibilityResultId().toString());
				assertThat(payload.path("data").path("averageMonthlyNetSales").asText()).isEqualTo("550000.00");
				assertThat(payload.path("data").has("monthlyMetrics")).isFalse();
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			s.setRollbackOnly();
		});
	}

	@Test
	void incompleteDataStillGetsAnActionRequiredCompletion() {
		var request = EligibilityTestContext.request();
		var c = EligibilityTestContext.context(request);
		var incomplete = new EligibilityInputGuard.Context(c.application(), c.verification(), c.consent(),
				EligibilityTestContext.source(request, true));
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			var done = pipeline.evaluate(request, incomplete, EligibilityTestContext.metadata());
			assertThat(done.result().result().outcome()).isEqualTo("ACTION_REQUIRED");
			assertThat(count("event_outbox", request.applicationId())).isEqualTo(1);
			s.setRollbackOnly();
		});
	}

	@Test
	void failedEventWriteRollsBackSourceSnapshotAndResult() {
		var request = EligibilityTestContext.request();
		var brokenJdbc = new JdbcTemplate(jdbc.getDataSource()) {
			@Override
			public int update(String sql, Object... args) {
				if (sql.startsWith("INSERT INTO event_outbox"))
					throw new DataIntegrityViolationException("Injected event write failure");
				return super.update(sql, args);
			}
		};
		var broken = new EligibilityTrustedPipeline(
				new EligibilityInputGuard(Clock.fixed(EligibilityTestContext.NOW, ZoneOffset.UTC)),
				OracleFinancialImportTest.pipeline,
				new EligibilityCompletionOutbox(new JdbcTestAccess(brokenJdbc), json));
		assertThatThrownBy(() -> OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> broken.evaluate(request,
				EligibilityTestContext.context(request), EligibilityTestContext.metadata())))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("financial_import_batch", request.applicationId())).isZero();
		assertThat(count("financial_snapshot", request.applicationId())).isZero();
		assertThat(count("eligibility_evaluation", request.applicationId())).isZero();
		assertThat(count("event_outbox", request.applicationId())).isZero();
	}

	@Test
	void failedGuardDoesNotImportData() {
		var request = EligibilityTestContext.request();
		var c = EligibilityTestContext.context(request);
		var consent = c.consent();
		var invalid = new EligibilityInputGuard.Context(c.application(), c.verification(),
				new EligibilityInputGuard.Consent(consent.applicationId(), consent.revision(), "REVOKED",
						consent.scope(), consent.expiresAt()),
				c.financialData());
		assertThatThrownBy(() -> OracleFinancialSnapshotTest.tx
				.executeWithoutResult(s -> pipeline.evaluate(request, invalid, EligibilityTestContext.metadata())))
				.isInstanceOf(EligibilityInputGuard.ContextRejectedException.class);
		assertThat(count("financial_import_batch", request.applicationId())).isZero();
	}

	@Test
	void concurrentRequestsStageOnlyOneCompletion() throws Exception {
		var request = EligibilityTestContext.request();
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			Callable<EligibilityTrustedPipeline.Completed> task = () -> {
				start.await();
				return OracleFinancialSnapshotTest.tx.execute(s -> pipeline.evaluate(request,
						EligibilityTestContext.context(request), EligibilityTestContext.metadata()));
			};
			var a = pool.submit(task);
			var b = pool.submit(task);
			start.countDown();
			assertThat(a.get(25, TimeUnit.SECONDS).eventId()).isEqualTo(b.get(25, TimeUnit.SECONDS).eventId());
			assertThat(count("event_outbox", request.applicationId())).isEqualTo(1);
		} finally {
			pool.shutdownNow();
			pool.awaitTermination(25, TimeUnit.SECONDS);
			cleanup(request.applicationId());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void failedSendRemainsPendingThenSuccessfulRetryMarksPublished() {
		var request = EligibilityTestContext.request();
		try {
			var done = OracleFinancialSnapshotTest.tx.execute(s -> pipeline.evaluate(request,
					EligibilityTestContext.context(request), EligibilityTestContext.metadata()));
			KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
			when(kafka.send(eq("eligibility.completed"), eq(request.applicationId().toString()), anyString()))
					.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")))
					.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
			var publisher = new EligibilityOutboxPublisher(new JdbcTestAccess(jdbc), kafka,
					OracleFinancialSnapshotTest.tx, 5);
			publisher.publishEvent(done.eventId());
			assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE event_id=?", Integer.class,
					done.eventId().toString())).isEqualTo(1);
			assertThat(
					jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_id=? AND published_at IS NULL",
							Integer.class, done.eventId().toString()))
					.isEqualTo(1);
			publisher.publishEvent(done.eventId());
			publisher.publishEvent(done.eventId());
			assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE event_id=?", Integer.class,
					done.eventId().toString())).isEqualTo(2);
			verify(kafka, times(2)).send(eq("eligibility.completed"), eq(request.applicationId().toString()),
					anyString());
		} finally {
			cleanup(request.applicationId());
		}
	}
}
