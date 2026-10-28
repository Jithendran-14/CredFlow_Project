package com.credflow.eligibility;

import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At-least-once delivery. Explicitly opt in only after the event contract is
 * accepted.
 */
@Component
@ConditionalOnProperty(name = "credflow.eligibility.publisher-enabled", havingValue = "true")
public class EligibilityOutboxPublisher {
	private static final Logger log = LoggerFactory.getLogger(EligibilityOutboxPublisher.class);
	private final DatabaseAccess database;
	private final KafkaTemplate<String, String> kafka;
	private final TransactionTemplate tx;
	private final int maxAttempts;

	public EligibilityOutboxPublisher(DatabaseAccess database, KafkaTemplate<String, String> kafka,
			TransactionTemplate tx, @Value("${credflow.eligibility.publisher-max-attempts:5}") int maxAttempts) {
		if (maxAttempts < 1)
			throw new IllegalArgumentException("Positive retry limit required");
		this.database = database;
		this.kafka = kafka;
		this.tx = tx;
		this.maxAttempts = maxAttempts;
	}

	@Scheduled(fixedDelayString = "${credflow.eligibility.publisher-interval-ms:2000}")
	public void publish() {
		publishPending(null);
	}

	/**
	 * Internal targeted delivery also keeps demonstrations away from unrelated
	 * pending rows.
	 */
	public void publishEvent(java.util.UUID eventId) {
		publishPending(java.util.Objects.requireNonNull(eventId));
	}

	private void publishPending(java.util.UUID eventId) {
		tx.executeWithoutResult(status -> {
			String base = "SELECT event_id,application_id FROM event_outbox WHERE event_type='eligibility.completed' AND published_at IS NULL AND attempts < ?";
			var rows = eventId == null
					? database.queryForList(base + " AND ROWNUM <= 20 FOR UPDATE SKIP LOCKED", maxAttempts)
					: database.queryForList(base + " AND event_id=? FOR UPDATE SKIP LOCKED", maxAttempts,
							eventId.toString());
			for (var row : rows) {
				String id = (String) row.get("EVENT_ID");
				try {
					String payload = database.queryForObject("SELECT payload FROM event_outbox WHERE event_id=?",
							String.class, id);
					kafka.send("eligibility.completed", (String) row.get("APPLICATION_ID"), payload).get(10,
							TimeUnit.SECONDS);
					database.update(
							"UPDATE event_outbox SET published_at=SYSTIMESTAMP,attempts=attempts+1 WHERE event_id=?",
							id);
				} catch (Exception ex) {
					database.update("UPDATE event_outbox SET attempts=attempts+1 WHERE event_id=?", id);
					log.warn("Eligibility delivery pending eventId={} errorType={}", id, ex.getClass().getSimpleName());
					if (ex instanceof InterruptedException) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		});
	}

	@Configuration
	@EnableScheduling
	@ConditionalOnProperty(name = "credflow.eligibility.publisher-enabled", havingValue = "true")
	static class Scheduling {
	}
}
