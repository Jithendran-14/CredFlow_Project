package com.credflow.application.outbox;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "credflow.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private static final String SUPPORTED_EVENT = "application.submitted";

	private final OutboxTransactionService transactions;
	private final KafkaTemplate<String, String> kafkaTemplate;

	public OutboxPublisher(OutboxTransactionService transactions, KafkaTemplate<String, String> kafkaTemplate) {

		this.transactions = transactions;
		this.kafkaTemplate = kafkaTemplate;
	}

	@Scheduled(fixedDelayString = "${credflow.outbox.poll-delay-ms:5000}", initialDelayString = "${credflow.outbox.initial-delay-ms:10000}")
	public void publishNext() {

		// This method intentionally has no @Transactional.
		try {
			var claimed = transactions.claimNext();

			if (claimed.isEmpty()) {
				return;
			}

			publish(claimed.get());

		} catch (Exception exception) {
			log.error("Outbox publisher cycle failed", exception);
		}
	}

	private void publish(ClaimedOutboxEvent event) {

		if (!java.util.Set.of("application.submitted", "application.revised").contains(event.eventType())) {
			log.error("Unsupported outbox event type: eventId={}, type={}", event.eventId(), event.eventType());

			recordFailure(event);
			return;
		}

		try {
			var result = kafkaTemplate.send(event.eventType(), event.aggregateId(), event.eventPayload()).get(130,
					TimeUnit.SECONDS);

			var metadata = result.getRecordMetadata();

			log.info("Kafka acknowledged outbox event: " + "eventId={}, partition={}, offset={}", event.eventId(),
					metadata.partition(), metadata.offset());

		} catch (InterruptedException exception) {

			Thread.currentThread().interrupt();

			// Leave PROCESSING for lease-based recovery.
			// The send may already have reached Kafka.
			log.warn("Outbox send interrupted; claim will expire: eventId={}", event.eventId());

			return;

		} catch (Exception exception) {

			log.error("Outbox Kafka send failed: eventId=" + event.eventId(), exception);

			recordFailure(event);
			return;
		}

		// Keep finalization separate from the send failure handler.
		// If Oracle fails here, recover through lease expiry.
		try {
			boolean updated = transactions.markPublished(event);

			if (updated) {
				log.info("Outbox event marked PUBLISHED: eventId={}", event.eventId());
			} else {
				log.warn("Outbox claim no longer owned after Kafka acknowledgment: " + "eventId={}", event.eventId());
			}

		} catch (Exception exception) {
			log.error("Kafka acknowledged event, but Oracle finalization failed; "
					+ "lease recovery may resend it: eventId=" + event.eventId(), exception);
		}
	}

	private void recordFailure(ClaimedOutboxEvent event) {

		try {
			boolean updated = transactions.markFailed(event);

			if (updated) {
				log.warn("Outbox event scheduled for retry: eventId={}", event.eventId());
			} else {
				log.warn("Could not record failure because claim is no longer owned: " + "eventId={}", event.eventId());
			}

		} catch (Exception exception) {
			log.error("Could not record outbox failure; claim will expire: eventId=" + event.eventId(), exception);
		}
	}
}