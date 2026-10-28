package com.credflow.eligibility;

import com.credflow.platform.persistence.DatabaseAccess;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "credflow.eligibility.worker-enabled=false",
		"credflow.eligibility.publisher-enabled=false" })
@EnabledIfEnvironmentVariable(named = "ORACLE_FINANCIAL_TESTS", matches = "true")
class OracleEligibilityProcessorTest {
	@Autowired
	DatabaseAccess database;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	EligibilityOperationStore operations;
	@Autowired
	FinancialEvaluationPipeline calculations;
	@Autowired
	EligibilityCompletionOutbox outbox;
	@Autowired
	PlatformTransactionManager manager;
	@MockitoBean
	JwtDecoder decoder;
	final List<EligibilityInputGuard.Request> cleanup = new ArrayList<>();
	TransactionTemplate tx;
	EligibilityTrustedPipeline pipeline;

	@BeforeEach
	void setup() {
		tx = new TransactionTemplate(manager);
		pipeline = new EligibilityTrustedPipeline(
				new EligibilityInputGuard(Clock.fixed(EligibilityTestContext.NOW, ZoneOffset.UTC)), calculations,
				outbox);
	}

	UUID enqueue(EligibilityInputGuard.Request r) {
		cleanup.add(r);
		return tx
				.execute(s -> operations.enqueue(
						new EligibilityOperationStore.Command(r.evaluationRequestId(), r.applicationId(), 1,
								r.verificationId(), r.asOfDate(), "trusted-test-reference"),
						"test-orchestration", "member2-local-demo"))
				.operationId();
	}

	EligibilityOperationProcessor processor(TrustedEligibilityContextProvider provider, int attempts) {
		return new EligibilityOperationProcessor(database, tx, pipeline, provider, attempts, 120);
	}

	TrustedEligibilityContextProvider.Trusted trusted(EligibilityInputGuard.Request r) {
		return new TrustedEligibilityContextProvider.Trusted(EligibilityTestContext.context(r),
				EligibilityTestContext.metadata());
	}

	@AfterEach
	void clean() {
		for (var r : cleanup) {
			jdbc.update("DELETE FROM eligibility_operation WHERE evaluation_request_id=?",
					r.evaluationRequestId().toString());
			OracleEligibilityCompletionTest.setup();
			OracleEligibilityCompletionTest.cleanup(r.applicationId());
		}
	}

	@Test
	void durableRequestBecomesStoredResultEventAndSucceededStatus() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		var p = processor(work -> trusted(r), 5);
		assertThat(p.process(id)).isEqualTo("SUCCEEDED");
		assertThat(p.process(id)).isEqualTo("IDLE");
		var status = operations.find(id, "test-orchestration").orElseThrow();
		assertThat(status.status()).isEqualTo("SUCCEEDED");
		assertThat(status.eligibilityResultId()).isNotNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE application_id=?", Integer.class,
				r.applicationId().toString())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT attempts FROM eligibility_operation WHERE operation_id=?", Integer.class,
				id.toString())).isEqualTo(1);
	}

	@Test
	void temporaryUpstreamFailureRetriesWithoutCreatingFinancialData() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		assertThat(processor(w -> {
			throw new TrustedEligibilityContextProvider.UpstreamNotReadyException();
		}, 5).process(id)).isEqualTo("RETRY_PENDING");
		assertThat(operations.find(id, "test-orchestration").orElseThrow().status()).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_import_batch WHERE application_id=?",
				Integer.class, r.applicationId().toString())).isZero();
	}

	@Test
	void boundedRetriesFinishAsFailureWithoutInventingAResult() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		assertThat(processor(w -> {
			throw new TrustedEligibilityContextProvider.UpstreamNotReadyException();
		}, 1).process(id)).isEqualTo("FAILED");
		var status = operations.find(id, "test-orchestration").orElseThrow();
		assertThat(status.status()).isEqualTo("FAILED");
		assertThat(status.eligibilityResultId()).isNull();
		assertThat(status.errorCode()).isEqualTo("UPSTREAM_NOT_READY");
	}

	@Test
	void staleWorkerCannotSaveAfterAnotherAttemptOwnsTheRequest() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		var p = processor(work -> {
			jdbc.update("UPDATE eligibility_operation SET attempts=attempts+1 WHERE operation_id=?", id.toString());
			return trusted(r);
		}, 5);
		assertThat(p.process(id)).isEqualTo("LEASE_LOST");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eligibility_evaluation WHERE application_id=?",
				Integer.class, r.applicationId().toString())).isZero();
	}

	@Test
	void expiredRunningClaimIsRecoveredAndTerminalRetriesCannotLoopForever() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		jdbc.update(
				"UPDATE eligibility_operation SET status='RUNNING',attempts=1,started_at=SYSTIMESTAMP-INTERVAL '10' MINUTE WHERE operation_id=?",
				id.toString());
		assertThat(processor(work -> trusted(r), 5).process(id)).isEqualTo("SUCCEEDED");
		assertThat(jdbc.queryForObject("SELECT attempts FROM eligibility_operation WHERE operation_id=?", Integer.class,
				id.toString())).isEqualTo(2);
	}

	@Test
	void invalidIdentityDoesNotLeavePartialFinancialResultOrCompletion() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		var original = EligibilityTestContext.context(r);
		var invalid = new EligibilityInputGuard.Context(original.application(),
				new EligibilityInputGuard.Verification(r.verificationId(), r.applicationId(), 1, "FAILED"),
				original.consent(), original.financialData());
		assertThat(processor(
				w -> new TrustedEligibilityContextProvider.Trusted(invalid, EligibilityTestContext.metadata()), 5)
				.process(id)).isEqualTo("FAILED");
		assertThat(operations.find(id, "test-orchestration").orElseThrow().errorCode())
				.isEqualTo("VERIFICATION_NOT_CURRENT_OR_VERIFIED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE application_id=?", Integer.class,
				r.applicationId().toString())).isZero();
	}

	@Test
	void twoWorkersDoNotFetchOrFinishTheSameLiveClaim() throws Exception {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		var claimed = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		var count = new java.util.concurrent.atomic.AtomicInteger();
		var p = processor(work -> {
			count.incrementAndGet();
			claimed.countDown();
			try {
				if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
					throw new IllegalStateException("Test claim timeout");
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return trusted(r);
		}, 5);
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var first = pool.submit(() -> p.process(id));
			assertThat(claimed.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			assertThat(p.process(id)).isEqualTo("IDLE");
			release.countDown();
			assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("SUCCEEDED");
			assertThat(count.get()).isEqualTo(1);
		} finally {
			release.countDown();
			pool.shutdownNow();
			pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	@Test
	void finalStatusFailureRollsBackFinancialRowsAndOutboxBeforeRetry() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		DatabaseAccess broken = new DatabaseAccess() {
			public int update(String sql, Object... args) {
				if (sql.startsWith("UPDATE eligibility_operation SET status='SUCCEEDED'"))
					throw new org.springframework.dao.DataIntegrityViolationException("Simulated final status failure");
				return database.update(sql, args);
			}

			public <T> List<T> query(String sql, Mapper<T> mapper, Object... args) {
				return database.query(sql, mapper, args);
			}

			public List<Map<String, Object>> queryForList(String sql, Object... args) {
				return database.queryForList(sql, args);
			}

			public String buildSnapshot(String app, int revision, LocalDate date, String batch) {
				return database.buildSnapshot(app, revision, date, batch);
			}
		};
		var p = new EligibilityOperationProcessor(broken, tx, pipeline, w -> trusted(r), 5, 120);
		assertThat(p.process(id)).isEqualTo("RETRY_PENDING");
		assertThat(operations.find(id, "test-orchestration").orElseThrow().status()).isEqualTo("PENDING");
		for (String table : List.of("financial_import_batch", "eligibility_evaluation", "event_outbox"))
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE application_id=?", Integer.class,
					r.applicationId().toString())).isZero();
	}

	@Test
	void crashedFinalAttemptIsMarkedFailedOnRecovery() {
		var r = EligibilityTestContext.request();
		UUID id = enqueue(r);
		jdbc.update(
				"UPDATE eligibility_operation SET status='RUNNING',attempts=5,started_at=SYSTIMESTAMP-INTERVAL '10' MINUTE WHERE operation_id=?",
				id.toString());
		assertThat(processor(w -> trusted(r), 5).process(id)).isEqualTo("IDLE");
		var status = operations.find(id, "test-orchestration").orElseThrow();
		assertThat(status.status()).isEqualTo("FAILED");
		assertThat(status.errorCode()).isEqualTo("WORKER_ATTEMPTS_EXHAUSTED");
	}
}
