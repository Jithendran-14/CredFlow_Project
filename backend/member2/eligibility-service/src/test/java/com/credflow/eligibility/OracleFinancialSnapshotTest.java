package com.credflow.eligibility;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Real Oracle only. Fixture writes roll back; schema is installed manually
 * before tests.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_FINANCIAL_TESTS", matches = "true")
class OracleFinancialSnapshotTest {
	static JdbcTemplate jdbc;
	static FinancialSnapshotRepository repo;
	static TransactionTemplate tx;
	final LocalDate asOf = LocalDate.of(2026, 10, 13);

	@BeforeAll
	static void setup() {
		String password = System.getenv("CF_ELIGIBILITY_PASSWORD");
		if (password == null)
			throw new IllegalStateException("Load local environment first");
		var ds = new DriverManagerDataSource(
				System.getenv().getOrDefault("ORACLE_JDBC_URL", "jdbc:oracle:thin:@localhost:1521/FREEPDB1"),
				"CF_ELIGIBILITY", password);
		jdbc = new JdbcTemplate(ds);
		repo = new FinancialSnapshotRepository(new JdbcTestAccess(jdbc));
		tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_errors WHERE name='CF_FINANCIAL_SUMMARY_PKG'",
				Integer.class)).isZero();
	}

	String batch(String app) {
		String batch = UUID.randomUUID().toString();
		jdbc.update(
				"INSERT INTO financial_import_batch(import_batch_id,application_id,application_revision,as_of_date,source_reference) VALUES(?,?,1,?,?)",
				batch, app, java.sql.Date.valueOf(asOf), "oracle-test");
		return batch;
	}

	void month(String batch, int offset, String gross, String returns, String coverage) {
		jdbc.update(
				"INSERT INTO financial_source_month(import_batch_id,month_start,coverage_status,gross_sales,returns_amount,payout_amount,order_count,returned_order_count) VALUES(?,?,?,?,?,?,?,?)",
				batch, java.sql.Date.valueOf(asOf.withDayOfMonth(1).minusMonths(offset)), coverage,
				new BigDecimal(gross), new BigDecimal(returns), new BigDecimal("530000.00"), 600, 50);
	}

	void fixture(String batch) {
		for (int i = 1; i <= 3; i++)
			month(batch, i, "600000.00", "50000.00", "COMPLETE");
	}

	@Test
	void realPackageAndJavaRulesMatchHappyPathAndRetryIsStable() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), batch = batch(app);
			fixture(batch);
			String snapshot = repo.build(app, 1, asOf, batch);
			assertThat(repo.build(app, 1, asOf, batch)).isEqualTo(snapshot);
			var r = new EligibilityCalculator().evaluate(repo.financials(snapshot), LocalDate.of(2024, 1, 1), asOf,
					new BigDecimal("500000.00"), "PURCHASE_INVENTORY", repo.activeProducts());
			assertThat(r.averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
			assertThat(r.averageMonthlyPayout()).isEqualByComparingTo("530000.00");
			assertThat(r.returnRatePercent()).isEqualByComparingTo("8.33");
			assertThat(r.lenderResults()).hasSize(2).allMatch(EligibilityCalculator.LenderResult::eligible);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_snapshot_month WHERE snapshot_id=?",
					Integer.class, snapshot)).isEqualTo(3);
			System.out.println(
					"ORACLE FINANCIAL CHECK: average net=550000.00, payout=530000.00, returns=8.33%, both products eligible");
			status.setRollbackOnly();
		});
	}

	@Test
	void missingMonthStaysIncompleteAndHasExplicitMissingRow() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), b = batch(app);
			month(b, 1, "600000", "50000", "COMPLETE");
			month(b, 2, "600000", "50000", "COMPLETE");
			String s = repo.build(app, 1, asOf, b);
			assertThat(repo.financials(s).reasonCode()).isEqualTo("FINANCIAL_DATA_INCOMPLETE");
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM financial_snapshot_month WHERE snapshot_id=? AND gross_sales IS NULL",
					Integer.class, s)).isEqualTo(1);
			status.setRollbackOnly();
		});
	}

	@Test
	void completeZeroMonthIsIncludedAndCurrentMonthExcluded() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), b = batch(app);
			month(b, 1, "600000", "50000", "COMPLETE");
			month(b, 2, "600000", "50000", "COMPLETE");
			month(b, 3, "0", "0", "COMPLETE");
			jdbc.update(
					"UPDATE financial_source_month SET payout_amount=0,order_count=0,returned_order_count=0 WHERE import_batch_id=? AND gross_sales=0",
					b);
			month(b, 0, "9999999", "0", "COMPLETE");
			var f = repo.financials(repo.build(app, 1, asOf, b));
			assertThat(f.outcome()).isEqualTo("EVALUATED");
			assertThat(f.gross()).isEqualByComparingTo("1200000.00");
			var r = new EligibilityCalculator().evaluate(f, LocalDate.of(2024, 1, 1), asOf, new BigDecimal("100000.00"),
					"WORKING_CAPITAL", repo.activeProducts());
			assertThat(r.averageMonthlyNetSales()).isEqualByComparingTo("366666.67");
			status.setRollbackOnly();
		});
	}

	@Test
	void allZeroMonthsRequireAction() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), b = batch(app);
			for (int i = 1; i <= 3; i++)
				month(b, i, "0", "0", "COMPLETE");
			assertThat(repo.financials(repo.build(app, 1, asOf, b)).reasonCode())
					.isEqualTo("FINANCIAL_DATA_INSUFFICIENT");
			status.setRollbackOnly();
		});
	}

	@Test
	void inconsistentReturnsAndCountsRequireAction() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), b = batch(app);
			fixture(b);
			jdbc.update("UPDATE financial_source_month SET returns_amount=gross_sales+1 WHERE import_batch_id=?", b);
			assertThat(repo.financials(repo.build(app, 1, asOf, b)).reasonCode())
					.isEqualTo("FINANCIAL_DATA_INCONSISTENT");
			String app2 = UUID.randomUUID().toString(), b2 = batch(app2);
			fixture(b2);
			jdbc.update("UPDATE financial_source_month SET returned_order_count=order_count+1 WHERE import_batch_id=?",
					b2);
			assertThat(repo.financials(repo.build(app2, 1, asOf, b2)).reasonCode())
					.isEqualTo("FINANCIAL_DATA_INCONSISTENT");
			status.setRollbackOnly();
		});
	}

	@Test
	void callerRollbackRemovesSnapshotAndImport() {
		String app = UUID.randomUUID().toString();
		tx.executeWithoutResult(status -> {
			String b = batch(app);
			fixture(b);
			repo.build(app, 1, asOf, b);
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_snapshot WHERE application_id=?", Integer.class,
				app)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_import_batch WHERE application_id=?",
				Integer.class, app)).isZero();
	}

	@Test
	void sourceRevisionMismatchCannotCreateSnapshot() {
		tx.executeWithoutResult(status -> {
			String app = UUID.randomUUID().toString(), b = batch(app);
			fixture(b);
			assertThatThrownBy(() -> repo.build(app, 2, asOf, b))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_snapshot WHERE application_id=?",
					Integer.class, app)).isZero();
			status.setRollbackOnly();
		});
	}
}
