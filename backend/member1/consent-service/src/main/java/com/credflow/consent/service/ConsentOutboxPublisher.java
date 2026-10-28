package com.credflow.consent.service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "credflow.outbox.enabled", havingValue = "true")
public class ConsentOutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(ConsentOutboxPublisher.class);

	private static final Set<String> ALLOWED_TOPICS = Set.of("verification.completed", "financial.consent.granted",
			"financial.consent.withdrawn", "financial.consent.expired");

	private final ConsentOutboxStore store;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper objectMapper;

	public ConsentOutboxPublisher(ConsentOutboxStore store, KafkaTemplate<String, String> kafka,
			ObjectMapper objectMapper) {

		this.store = store;
		this.kafka = kafka;
		this.objectMapper = objectMapper;
	}

	@Scheduled(fixedDelayString = "${credflow.outbox.fixed-delay-ms:5000}")
	public void publishNext() {

		try {
			var candidate = store.claimNext();

			if (candidate.isEmpty()) {
				return;
			}

			publish(candidate.get());

		} catch (Exception exception) {
			log.error("Consent outbox poll failed: errorType={}", exception.getClass().getSimpleName());
		}
	}

	private void publish(ConsentOutboxStore.ClaimedEvent event) {

		try {
			if (!ALLOWED_TOPICS.contains(event.eventType())) {
				store.markFailed(event, "UNSUPPORTED_OUTBOX_EVENT_TYPE", true);
				return;
			}

			var payload = objectMapper.readTree(event.payload());

			boolean matches = event.eventId().equals(payload.path("eventId").asText())
					&& event.applicationId().equals(payload.path("applicationId").asText())
					&& event.eventType().equals(payload.path("eventType").asText())
					&& "1.0".equals(payload.path("schemaVersion").asText())
					&& "consent-verification-service".equals(payload.path("producer").asText());

			if (!matches) {
				store.markFailed(event, "OUTBOX_ENVELOPE_MISMATCH", true);
				return;
			}

			// Wait for Kafka acknowledgement before updating Oracle.
			kafka.send(event.eventType(), event.applicationId(), event.payload()).get(25, TimeUnit.SECONDS);

			boolean updated = store.markPublished(event);

			if (updated) {
				log.info("Consent event published: eventId={}, topic={}", event.eventId(), event.eventType());
			} else {
				log.warn("Kafka acknowledged event but claim changed: " + "eventId={}", event.eventId());
			}

		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			// Leave the claim for lease recovery during shutdown.
			log.info("Consent publisher interrupted: eventId={}", event.eventId());

		} catch (Exception exception) {
			store.markFailed(event, exception.getClass().getSimpleName(), false);

			log.warn("Consent event publishing failed: " + "eventId={}, errorType={}", event.eventId(),
					exception.getClass().getSimpleName());
		}
	}
}