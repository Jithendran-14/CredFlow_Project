package com.credflow.eligibility;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FinancialSnapshotRepository {
	private final DatabaseAccess database;

	public FinancialSnapshotRepository(DatabaseAccess database) {
		this.database = database;
	}

	/**
	 * Internal repository operation; HTTP authorisation belongs at the future
	 * service boundary.
	 */
	@Transactional
	public String build(String applicationId, int revision, LocalDate asOf, String batchId) {
		return database.buildSnapshot(applicationId, revision, asOf, batchId);
	}

	public EligibilityCalculator.Financials financials(String snapshotId) {
		return database.queryForObject(
				"SELECT S.outcome,S.reason_code,S.total_gross,S.total_returns,S.total_payout,B.credit_score_band FROM financial_snapshot S JOIN financial_import_batch B ON B.import_batch_id=S.import_batch_id WHERE S.snapshot_id=?",
				(rs, n) -> new EligibilityCalculator.Financials(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
						rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6)),
				snapshotId);
	}

	public List<EligibilityCalculator.Product> activeProducts() {
		return database.query(
				"SELECT r.* FROM lender_product_rule r WHERE active=1 AND rule_version=(SELECT MAX(v.rule_version) FROM lender_product_rule v WHERE v.product_id=r.product_id) ORDER BY product_id",
				(rs, n) -> new EligibilityCalculator.Product(rs.getString("product_id"), rs.getString("lender_id"),
						rs.getInt("rule_version"), rs.getInt("min_age_months"), rs.getBigDecimal("min_average_net"),
						rs.getBigDecimal("max_return_percent"), rs.getBigDecimal("min_amount"),
						rs.getBigDecimal("max_amount"), rs.getBigDecimal("sales_multiplier"),
						rs.getInt("routing_priority"),
						new HashSet<>(database.queryForList(
								"SELECT purpose FROM lender_product_purpose WHERE product_id=? AND rule_version=?",
								String.class, rs.getString("product_id"), rs.getInt("rule_version"))),
						rs.getString("min_credit_score_band"), rs.getBigDecimal("max_repayment_percent"),
						rs.getBigDecimal("assessment_apr_percent")));
	}
}
