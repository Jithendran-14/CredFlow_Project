package com.credflow.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Accepts already-authorised internal data. Does not fetch external data or
 * grant consent.
 */
@Service
public class FinancialImportService {
	public enum Coverage {
		COMPLETE, INCOMPLETE
	}

	public record MonthlyMetrics(YearMonth month, Coverage coverage, BigDecimal grossSales, BigDecimal returnsAmount,
			BigDecimal payoutAmount, Long orderCount, Long returnedOrderCount) {
	}

	public record Command(UUID applicationId, int applicationRevision, LocalDate asOfDate, String sourceProvider,
			String dataReference, int dataVersion, Instant retrievedAt, String currency, List<MonthlyMetrics> months,
			String creditScoreBand) {
		public Command(UUID app, int revision, LocalDate asOf, String provider, String reference, int version,
				Instant at, String currency, List<MonthlyMetrics> months) {
			this(app, revision, asOf, provider, reference, version, at, currency, months, "NOT_AVAILABLE");
		}

		public Command {
			months = List.copyOf(Objects.requireNonNull(months));
		}
	}

	public record ImportedSnapshot(UUID importBatchId, UUID financialSnapshotId, int dataVersion, String outcome,
			String reasonCode) {
	}

	public static class SourceDataConflictException extends IllegalArgumentException {
		public SourceDataConflictException() {
			super("Source reference/version was reused with changed financial data");
		}
	}

	private final DatabaseAccess database;
	private final FinancialSnapshotRepository snapshots;
	private final ObjectMapper json;

	public FinancialImportService(DatabaseAccess database, FinancialSnapshotRepository snapshots, ObjectMapper json) {
		this.database = database;
		this.snapshots = snapshots;
		this.json = json;
	}

	@Transactional
	public ImportedSnapshot importAndBuild(Command supplied) {
		if (!TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("Financial import requires a database transaction");
		Command c = normalize(supplied);
		String hash = hash(c);
		var existing = find(c);
		if (!existing.isEmpty())
			return reuse(c, hash, existing.getFirst());
		String batchId = UUID.randomUUID().toString();
		try {
			database.update(
					"INSERT INTO financial_import_batch(import_batch_id,application_id,application_revision,as_of_date,currency,source_reference,source_provider,source_data_version,input_hash,source_retrieved_at,credit_score_band) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
					batchId, c.applicationId().toString(), c.applicationRevision(), java.sql.Date.valueOf(c.asOfDate()),
					c.currency(), c.dataReference(), c.sourceProvider(), c.dataVersion(), hash,
					c.retrievedAt().atOffset(ZoneOffset.UTC), c.creditScoreBand());
		} catch (DuplicateKeyException duplicate) {
			// Unique source key serializes concurrent imports, including identical
			// deliveries.
			existing = find(c);
			if (existing.isEmpty())
				throw duplicate;
			return reuse(c, hash, existing.getFirst());
		}
		for (var m : c.months()) {
			database.update(
					"INSERT INTO financial_source_month(import_batch_id,month_start,coverage_status,gross_sales,returns_amount,payout_amount,order_count,returned_order_count) VALUES(?,?,?,?,?,?,?,?)",
					batchId, java.sql.Date.valueOf(m.month().atDay(1)), m.coverage().name(), m.grossSales(),
					m.returnsAmount(), m.payoutAmount(), m.orderCount(), m.returnedOrderCount());
		}
		return build(c, batchId);
	}

	private List<Map<String, Object>> find(Command c) {
		return database.queryForList(
				"SELECT import_batch_id,input_hash FROM financial_import_batch WHERE application_id=? AND application_revision=? AND as_of_date=? AND source_provider=? AND source_reference=? AND source_data_version=?",
				c.applicationId().toString(), c.applicationRevision(), java.sql.Date.valueOf(c.asOfDate()),
				c.sourceProvider(), c.dataReference(), c.dataVersion());
	}

	private ImportedSnapshot reuse(Command c, String hash, Map<String, Object> row) {
		if (!hash.equals(row.get("INPUT_HASH")))
			throw new SourceDataConflictException();
		return build(c, (String) row.get("IMPORT_BATCH_ID"));
	}

	private ImportedSnapshot build(Command c, String batchId) {
		String id = snapshots.build(c.applicationId().toString(), c.applicationRevision(), c.asOfDate(), batchId);
		var financials = snapshots.financials(id);
		return new ImportedSnapshot(UUID.fromString(batchId), UUID.fromString(id), c.dataVersion(),
				financials.outcome(), financials.reasonCode());
	}

	static Command normalize(Command c) {
		Objects.requireNonNull(c, "Financial import is required");
		Objects.requireNonNull(c.applicationId());
		Objects.requireNonNull(c.asOfDate());
		Objects.requireNonNull(c.retrievedAt());
		if (c.applicationRevision() < 1 || c.dataVersion() < 1 || !"INR".equals(c.currency())
				|| c.sourceProvider() == null || c.sourceProvider().isBlank() || c.sourceProvider().length() > 100
				|| c.dataReference() == null || c.dataReference().isBlank() || c.dataReference().length() > 200)
			throw new IllegalArgumentException("Invalid financial source identity, revision or currency");
		YearMonth end = YearMonth.from(c.asOfDate()).minusMonths(1), start = end.minusMonths(2);
		Map<YearMonth, MonthlyMetrics> byMonth = new HashMap<>();
		for (var m : c.months()) {
			Objects.requireNonNull(m);
			Objects.requireNonNull(m.month());
			Objects.requireNonNull(m.coverage());
			if (m.month().isBefore(start) || m.month().isAfter(end))
				throw new IllegalArgumentException(
						"Financial month is outside the last three complete calendar months");
			BigDecimal gross = money(m.grossSales()), returns = money(m.returnsAmount()),
					payout = money(m.payoutAmount());
			Long orders = count(m.orderCount()), returned = count(m.returnedOrderCount());
			Coverage coverage = m.coverage();
			if (gross == null || returns == null || payout == null || orders == null || returned == null)
				coverage = Coverage.INCOMPLETE;
			var normalized = new MonthlyMetrics(m.month(), coverage, gross, returns, payout, orders, returned);
			if (byMonth.putIfAbsent(m.month(), normalized) != null)
				throw new IllegalArgumentException("Duplicate financial month");
		}
		List<MonthlyMetrics> normalized = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			YearMonth month = start.plusMonths(i);
			normalized.add(byMonth.getOrDefault(month,
					new MonthlyMetrics(month, Coverage.INCOMPLETE, null, null, null, null, null)));
		}
		return new Command(c.applicationId(), c.applicationRevision(), c.asOfDate(), c.sourceProvider(),
				c.dataReference(), c.dataVersion(), c.retrievedAt(), c.currency(), normalized,
				c.creditScoreBand() == null ? "NOT_AVAILABLE" : c.creditScoreBand());
	}

	private static BigDecimal money(BigDecimal value) {
		if (value == null)
			return null;
		if (value.signum() < 0)
			throw new IllegalArgumentException("Negative source amounts are not accepted");
		BigDecimal exact;
		try {
			exact = value.setScale(2, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("Source money must have at most two meaningful decimal places", ex);
		}
		if (exact.precision() - exact.scale() > 17)
			throw new IllegalArgumentException("Source amount exceeds storage precision");
		return exact;
	}

	private static Long count(Long value) {
		if (value != null && (value < 0 || value > 9_999_999_999L))
			throw new IllegalArgumentException("Source count is outside storage range");
		return value;
	}

	private String hash(Command c) {
		try {
			var months = c.months().stream()
					.map(m -> Arrays.asList(m.month().toString(), m.coverage().name(),
							m.grossSales() == null ? null : m.grossSales().toPlainString(),
							m.returnsAmount() == null ? null : m.returnsAmount().toPlainString(),
							m.payoutAmount() == null ? null : m.payoutAmount().toPlainString(),
							m.orderCount() == null ? null : m.orderCount().toString(),
							m.returnedOrderCount() == null ? null : m.returnedOrderCount().toString()))
					.toList();
			// Retrieval time is observation metadata; a retry of the same source version
			// may occur later.
			byte[] canonical = json.writeValueAsBytes(List.of(c.applicationId().toString(), c.applicationRevision(),
					c.asOfDate().toString(), c.sourceProvider(), c.dataReference(), c.dataVersion(), c.currency(),
					months, c.creditScoreBand()));
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot fingerprint financial source", ex);
		}
	}
}
