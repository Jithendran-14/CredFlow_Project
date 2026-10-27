package com.credflow.application.kafka;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "credflow.kafka",
        name = "smoke-test-enabled",
        havingValue = "true"
)
public class KafkaSmokeTestRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaSmokeTestRunner.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaSmokeTestRunner(
            KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {

        String eventId = UUID.randomUUID().toString();

        String payload = """
                {
                  "eventId": "%s",
                  "eventType": "kafka.smoke-test",
                  "message": "Message from Application Service"
                }
                """.formatted(eventId);

        log.info("Sending Kafka smoke test: eventId={}", eventId);

        kafkaTemplate.send(
                "application.submitted",
                eventId,
                payload
        ).whenComplete((result, exception) -> {

            if (exception != null) {
                log.error(
                        "Kafka smoke test failed: eventId=" + eventId,
                        exception
                );
                return;
            }

            var metadata = result.getRecordMetadata();

            log.info(
                    "Kafka smoke test acknowledged: eventId={}, "
                            + "topic={}, partition={}, offset={}",
                    eventId,
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset()
            );
        });
    }
}