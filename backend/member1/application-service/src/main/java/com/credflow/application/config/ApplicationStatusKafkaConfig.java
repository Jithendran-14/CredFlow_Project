package com.credflow.application.config;

import java.util.HashMap;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.backoff.FixedBackOff;

import com.credflow.application.service.ApplicationStatusInbox;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "credflow.status-consumer.enabled",
        havingValue = "true"
)
public class ApplicationStatusKafkaConfig {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ApplicationStatusKafkaConfig.class
            );

    private final ApplicationStatusInbox inbox;

    public ApplicationStatusKafkaConfig(
            ApplicationStatusInbox inbox) {
        this.inbox = inbox;
    }

    @Bean
    public static ConcurrentKafkaListenerContainerFactory<String, String>
            applicationStatusContainerFactory(
                @Value("${spring.kafka.bootstrap-servers}")
                String brokers) {

        var properties = new HashMap<String, Object>();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                brokers
        );
        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        properties.put(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                50
        );

        var factory =
                new ConcurrentKafkaListenerContainerFactory<
                        String, String>();

        factory.setConsumerFactory(
                new DefaultKafkaConsumerFactory<>(properties)
        );

        factory.getContainerProperties().setAckMode(
                ContainerProperties.AckMode.RECORD
        );

        // A DB failure must not discard an unrecorded Kafka message.
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(
                        new FixedBackOff(
                                2000,
                                FixedBackOff.UNLIMITED_ATTEMPTS
                        )
                )
        );

        return factory;
    }

    @KafkaListener(
        topics = "application.status.changed",
        groupId =
            "${credflow.status-consumer.group-id:application-status-v1}",
        containerFactory = "applicationStatusContainerFactory"
    )
    public void receive(ConsumerRecord<String, String> record) {
        inbox.receive(record);
    }

    @Scheduled(
        fixedDelayString =
            "${credflow.status-consumer.poll-delay-ms:2000}"
    )
    public void project() {

        try {
            for (Long id : inbox.pending()) {
                try {
                    inbox.apply(id);

                } catch (Exception exception) {
                    log.warn(
                            "Workflow projection failed: inboxId={}",
                            id,
                            exception
                    );
                    inbox.retry(id);
                }
            }

        } catch (Exception exception) {
            log.error("Workflow inbox poll failed", exception);
        }
    }
}