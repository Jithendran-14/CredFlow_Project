package com.credflow.eligibility;

import com.credflow.platform.persistence.DatabaseAccess;
import java.time.LocalDate;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims are committed before upstream I/O. Attempts fence late workers;
 * result/outbox/status commit together.
 */
public final class EligibilityOperationProcessor {
	public record Work(UUID operationId, EligibilityOperationStore.Command command, String correlationId, int attempt) {
	}

	private final DatabaseAccess database;
	private final TransactionTemplate tx;
	private final EligibilityTrustedPipeline pipeline;
	private final TrustedEligibilityContextProvider provider;
	private final int maxAttempts, leaseSeconds;

	public EligibilityOperationProcessor(DatabaseAccess database, TransactionTemplate tx,
			EligibilityTrustedPipeline pipeline, TrustedEligibilityContextProvider provider, int maxAttempts,
			int leaseSeconds) {
		this.database = Objects.requireNonNull(database);
		this.tx = Objects.requireNonNull(tx);
		this.pipeline = Objects.requireNonNull(pipeline);
		this.provider = Objects.requireNonNull(provider);
		if (maxAttempts < 1 || leaseSeconds < 1)
			throw new IllegalArgumentException("Positive worker limits required");
		this.maxAttempts = maxAttempts;
		this.leaseSeconds = leaseSeconds;
	}

	public String processNext() {
		return process(null);
	}

	/**
	 * Targeted processing is also used by tests/operations without draining
	 * unrelated queued requests.
	 */
	public String process(UUID operationId) {
		Work work = tx.execute(s -> claim(operationId));
		if (work == null)
			return "IDLE";
		try {
			// The adapter must obtain current, authenticated context for exactly this
			// revision/reference.
			var trusted = Objects.requireNonNull(provider.fetch(work));
			return tx.execute(s -> {
				if (!owns(work))
					return "LEASE_LOST";
				if (!work.correlationId().equals(trusted.eventMetadata().correlationId()))
					throw new IllegalArgumentException("Correlation mismatch");
				var command = work.command();
				var request = new EligibilityInputGuard.Request(command.evaluationRequestId(), command.applicationId(),
						command.applicationRevision(), command.verificationId(), command.asOfDate());
				var result = pipeline.evaluate(request, trusted.context(), trusted.eventMetadata());
				database.update(
						"UPDATE eligibility_operation SET status='SUCCEEDED',eligibility_result_id=?,finished_at=SYSTIMESTAMP,error_code=NULL WHERE operation_id=? AND status='RUNNING' AND attempts=?",
						result.result().eligibilityResultId().toString(), work.operationId().toString(),
						work.attempt());
				return "SUCCEEDED";
			});
		} catch (RuntimeException failure) {
			boolean permanent = failure instanceof IllegalArgumentException;
			String code = failure instanceof EligibilityInputGuard.ContextRejectedException rejected ? rejected.code()
					: failure instanceof TrustedEligibilityContextProvider.UpstreamNotReadyException
							? "UPSTREAM_NOT_READY"
							: "ELIGIBILITY_PROCESSING_FAILED";
			return tx.execute(s -> {
				if (!owns(work))
					return "LEASE_LOST";
				boolean terminal = permanent || work.attempt() >= maxAttempts;
				database.update(
						"UPDATE eligibility_operation SET status=?,error_code=?,finished_at="
								+ (terminal ? "SYSTIMESTAMP" : "NULL")
								+ " WHERE operation_id=? AND status='RUNNING' AND attempts=?",
						terminal ? "FAILED" : "PENDING", code, work.operationId().toString(), work.attempt());
				return terminal ? "FAILED" : "RETRY_PENDING";
			});
		}
	}

	private Work claim(UUID requested) {
		String sql = "SELECT operation_id,evaluation_request_id,application_id,application_revision,verification_id,as_of_date,retrieval_reference,correlation_id,attempts FROM eligibility_operation WHERE (status='PENDING' OR (status='RUNNING' AND started_at<SYSTIMESTAMP-NUMTODSINTERVAL(?,'SECOND')))";
		Object[] args = requested == null ? new Object[] { leaseSeconds }
				: new Object[] { leaseSeconds, requested.toString() };
		if (requested != null)
			sql += " AND operation_id=?";
		var rows = database.query(sql + " AND ROWNUM<=1 FOR UPDATE SKIP LOCKED",
				(r, n) -> new Work(UUID.fromString(r.getString("operation_id")),
						new EligibilityOperationStore.Command(UUID.fromString(r.getString("evaluation_request_id")),
								UUID.fromString(r.getString("application_id")), r.getInt("application_revision"),
								UUID.fromString(r.getString("verification_id")), r.getDate("as_of_date").toLocalDate(),
								r.getString("retrieval_reference")),
						r.getString("correlation_id"), r.getInt("attempts") + 1),
				args);
		if (rows.isEmpty())
			return null;
		Work work = rows.getFirst();
		if (work.attempt() > maxAttempts) {
			database.update(
					"UPDATE eligibility_operation SET status='FAILED',error_code='WORKER_ATTEMPTS_EXHAUSTED',finished_at=SYSTIMESTAMP WHERE operation_id=?",
					work.operationId().toString());
			return null;
		}
		database.update(
				"UPDATE eligibility_operation SET status='RUNNING',attempts=?,started_at=SYSTIMESTAMP,finished_at=NULL,error_code=NULL WHERE operation_id=?",
				work.attempt(), work.operationId().toString());
		return work;
	}

	private boolean owns(Work work) {
		var rows = database.queryForList(
				"SELECT status,attempts FROM eligibility_operation WHERE operation_id=? FOR UPDATE",
				work.operationId().toString());
		return !rows.isEmpty() && "RUNNING".equals(rows.getFirst().get("STATUS"))
				&& ((Number) rows.getFirst().get("ATTEMPTS")).intValue() == work.attempt();
	}
}
