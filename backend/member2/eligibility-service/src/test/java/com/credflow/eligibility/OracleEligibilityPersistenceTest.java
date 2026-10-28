package com.credflow.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ORACLE_FINANCIAL_TESTS", matches = "true")
class OracleEligibilityPersistenceTest {
	static EligibilityEvaluationService service;
	final LocalDate asOf = LocalDate.of(2026, 10, 13);

	@BeforeAll
	static void setup() {
		OracleFinancialSnapshotTest.setup();
		service = new EligibilityEvaluationService(new JdbcTestAccess(OracleFinancialSnapshotTest.jdbc),
				OracleFinancialSnapshotTest.repo, new ObjectMapper());
	}

	EligibilityEvaluationService.Command fixture(boolean complete, String purpose) {
		var jdbc = OracleFinancialSnapshotTest.jdbc;
		UUID app = UUID.randomUUID();
		String batch = UUID.randomUUID().toString();
		jdbc.update(
				"INSERT INTO financial_import_batch(import_batch_id,application_id,application_revision,as_of_date,source_reference) VALUES(?,?,1,?,'persistence-test')",
				batch, app.toString(), java.sql.Date.valueOf(asOf));
		for (int i = 1; i <= (complete ? 3 : 2); i++)
			jdbc.update(
					"INSERT INTO financial_source_month(import_batch_id,month_start,coverage_status,gross_sales,returns_amount,payout_amount,order_count,returned_order_count) VALUES(?,?,'COMPLETE',600000,50000,530000,600,50)",
					batch, java.sql.Date.valueOf(asOf.withDayOfMonth(1).minusMonths(i)));
		String snapshot = OracleFinancialSnapshotTest.repo.build(app.toString(), 1, asOf, batch);
		return new EligibilityEvaluationService.Command(UUID.randomUUID(), app, 1, UUID.fromString(snapshot),
				UUID.randomUUID(), LocalDate.of(2024, 1, 1), asOf, new BigDecimal("500000.00"), "INR", purpose);
	}

	EligibilityEvaluationService.Command changed(EligibilityEvaluationService.Command c, BigDecimal amount, int rev,
			LocalDate date, UUID app) {
		return new EligibilityEvaluationService.Command(c.evaluationRequestId(), app, rev, c.financialSnapshotId(),
				c.verificationId(), c.businessStartDate(), date, amount, c.currency(), c.loanPurpose());
	}

	int count(String request) {
		return OracleFinancialSnapshotTest.jdbc.queryForObject(
				"SELECT COUNT(*) FROM eligibility_evaluation WHERE evaluation_request_id=?", Integer.class, request);
	}

	@Test
	void storesHeaderBothLendersAndReadsTheOriginalResult() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var c = fixture(true, "PURCHASE_INVENTORY");
			var saved = service.evaluateAndSave(c);
			assertThat(saved.eligibilityResultId()).isNotNull();
			assertThat(saved.evaluatedAt()).isNotNull();
			assertThat(saved.applicationRevision()).isEqualTo(1);
			assertThat(saved.verificationId()).isEqualTo(c.verificationId());
			assertThat(saved.result().averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
			assertThat(saved.result().lenderResults()).hasSize(2)
					.allMatch(EligibilityCalculator.LenderResult::eligible);
			assertThat(service.findById(saved.eligibilityResultId())).contains(saved);
			System.out.println(
					"ORACLE STORAGE CHECK: saved one evaluation and two eligible lender results; read-back matched");
			status.setRollbackOnly();
		});
	}

	@Test
	void repeatedRequestReturnsSameResultEvenWhenRulesChange() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var c = fixture(true, "WORKING_CAPITAL");
			var first = service.evaluateAndSave(c);
			OracleFinancialSnapshotTest.jdbc.update("UPDATE lender_product_rule SET active=0");
			var repeated = service.evaluateAndSave(changed(c, new BigDecimal("500000"), 1, asOf, c.applicationId()));
			assertThat(repeated).isEqualTo(first);
			assertThat(count(c.evaluationRequestId().toString())).isEqualTo(1);
			status.setRollbackOnly();
		});
	}

	@Test
	void reusedRequestWithChangedAmountIsAConflict() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var c = fixture(true, "WORKING_CAPITAL");
			service.evaluateAndSave(c);
			assertThatThrownBy(
					() -> service.evaluateAndSave(changed(c, new BigDecimal("400000.00"), 1, asOf, c.applicationId())))
					.isInstanceOf(EligibilityEvaluationService.EvaluationConflictException.class);
			assertThat(count(c.evaluationRequestId().toString())).isEqualTo(1);
			status.setRollbackOnly();
		});
	}

	@Test
	void wrongApplicationRevisionAndReportingDateAreRefused() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var c = fixture(true, "WORKING_CAPITAL");
			for (var bad : List.of(changed(c, c.requestedAmount(), 2, asOf, c.applicationId()),
					changed(c, c.requestedAmount(), 1, asOf.plusDays(1), c.applicationId()),
					changed(c, c.requestedAmount(), 1, asOf, UUID.randomUUID()))) {
				assertThatThrownBy(() -> service.evaluateAndSave(bad))
						.isInstanceOf(EligibilityEvaluationService.EvaluationConflictException.class);
			}
			assertThat(count(c.evaluationRequestId().toString())).isZero();
			status.setRollbackOnly();
		});
	}

	@Test
	void missingDataStoresActionRequiredWithoutInventedLenderResults() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var saved = service.evaluateAndSave(fixture(false, "WORKING_CAPITAL"));
			assertThat(saved.result().outcome()).isEqualTo("ACTION_REQUIRED");
			assertThat(saved.result().reasonCodes()).containsExactly("FINANCIAL_DATA_INCOMPLETE");
			assertThat(saved.result().averageMonthlyNetSales()).isNull();
			assertThat(saved.result().lenderResults()).isEmpty();
			assertThat(service.findById(saved.eligibilityResultId())).contains(saved);
			status.setRollbackOnly();
		});
	}

	@Test
	void storesExplanationsWhenNeitherProductSupportsThePurpose() {
		OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var saved = service.evaluateAndSave(fixture(true, "BUSINESS_EXPANSION"));
			assertThat(saved.result().outcome()).isEqualTo("EVALUATED");
			assertThat(saved.result().lenderResults()).hasSize(2).allSatisfy(l -> {
				assertThat(l.eligible()).isFalse();
				assertThat(l.reasonCodes()).containsExactly("PURPOSE_NOT_SUPPORTED");
			});
			status.setRollbackOnly();
		});
	}

	@Test
	void failedLenderWriteRollsBackHeaderToo() {
		UUID request = UUID.randomUUID();
		assertThatThrownBy(() -> OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
			var original = fixture(true, "WORKING_CAPITAL");
			var c = new EligibilityEvaluationService.Command(request, original.applicationId(), 1,
					original.financialSnapshotId(), original.verificationId(), original.businessStartDate(), asOf,
					original.requestedAmount(), "INR", "WORKING_CAPITAL");
			var brokenRepo = new FinancialSnapshotRepository(new JdbcTestAccess(OracleFinancialSnapshotTest.jdbc)) {
				@Override
				public List<EligibilityCalculator.Product> activeProducts() {
					return List.of(new EligibilityCalculator.Product(UUID.randomUUID().toString(),
							UUID.randomUUID().toString(), 1, 0, BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ONE,
							new BigDecimal("1000000"), BigDecimal.ONE, 1, Set.of("WORKING_CAPITAL")));
				}
			};
			new EligibilityEvaluationService(new JdbcTestAccess(OracleFinancialSnapshotTest.jdbc), brokenRepo,
					new ObjectMapper()).evaluateAndSave(c);
		})).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count(request.toString())).isZero();
	}

	@Test
	void concurrentRetriesCommitOneEvaluationAndShareItsId() throws Exception {
		var c = OracleFinancialSnapshotTest.tx.execute(status -> fixture(true, "WORKING_CAPITAL"));
		var executor = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			Callable<UUID> task = () -> {
				start.await();
				return OracleFinancialSnapshotTest.tx
						.execute(status -> service.evaluateAndSave(c).eligibilityResultId());
			};
			var a = executor.submit(task);
			var b = executor.submit(task);
			start.countDown();
			assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
			assertThat(count(c.evaluationRequestId().toString())).isEqualTo(1);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(25, TimeUnit.SECONDS);
			OracleFinancialSnapshotTest.tx.executeWithoutResult(status -> {
				var jdbc = OracleFinancialSnapshotTest.jdbc;
				String app = c.applicationId().toString();
				jdbc.update(
						"DELETE FROM eligibility_lender_result WHERE eligibility_result_id IN (SELECT eligibility_result_id FROM eligibility_evaluation WHERE application_id=?)",
						app);
				jdbc.update("DELETE FROM eligibility_evaluation WHERE application_id=?", app);
				jdbc.update("DELETE FROM financial_snapshot_month WHERE snapshot_id=?",
						c.financialSnapshotId().toString());
				jdbc.update("DELETE FROM financial_snapshot WHERE application_id=?", app);
				jdbc.update(
						"DELETE FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?)",
						app);
				jdbc.update("DELETE FROM financial_import_batch WHERE application_id=?", app);
			});
		}
	}

	@Test
	void missingReadReturnsEmptyAndUntransactionalWritesAreRefused() {
		assertThat(service.findById(UUID.randomUUID())).isEmpty();
		assertThatThrownBy(() -> service.evaluateAndSave(null)).isInstanceOf(IllegalStateException.class);
	}
}
