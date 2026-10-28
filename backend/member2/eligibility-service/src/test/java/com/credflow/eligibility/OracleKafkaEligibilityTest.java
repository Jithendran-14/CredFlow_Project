package com.credflow.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Real Oracle outbox to temporary Kafka; no teammate API or Orchestration
 * consumption is claimed.
 */
@SpringBootTest(properties = { "credflow.eligibility.publisher-enabled=false", "logging.level.org.apache.kafka=ERROR",
		"logging.level.kafka=ERROR", "logging.level.state.change.logger=ERROR" })
@EmbeddedKafka(partitions = 1, kraft = true, topics = {
		"eligibility.completed" }, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "ORACLE_KAFKA_TESTS", matches = "true")
class OracleKafkaEligibilityTest {
	@Autowired
	EmbeddedKafkaBroker broker;
	@Autowired
	KafkaTemplate<String, String> kafka;
	@Autowired
	com.credflow.platform.persistence.DatabaseAccess database;
	@Autowired
	FinancialEvaluationPipeline jpaPipeline;
	@Autowired
	EligibilityCompletionOutbox jpaOutbox;
	@Autowired
	org.springframework.transaction.PlatformTransactionManager transactionManager;
	@MockitoBean
	JwtDecoder decoder;

	@Test
	void committedOracleOutboxReachesRealKafkaAndIsMarkedPublished() throws Exception {
		OracleEligibilityCompletionTest.setup();
		var jdbc = OracleEligibilityCompletionTest.jdbc;
		var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		var request = EligibilityTestContext.request();
		Map<String, Object> properties = new HashMap<>();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, "eligibility-proof-" + UUID.randomUUID());
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (var consumer = new KafkaConsumer<String, String>(properties)) {
			broker.consumeFromAnEmbeddedTopic(consumer, "eligibility.completed");
			var completed = tx.execute(s -> {
				var result = jpaPipeline.evaluate(EligibilityTestContext.source(request, false),
						request.evaluationRequestId(), request.verificationId(), java.time.LocalDate.of(2024, 1, 1),
						new java.math.BigDecimal("500000.00"), "WORKING_CAPITAL");
				return new EligibilityTrustedPipeline.Completed(result,
						jpaOutbox.record(result, EligibilityTestContext.metadata()));
			});
			String payload = jdbc.queryForObject("SELECT payload FROM event_outbox WHERE event_id=?", String.class,
					completed.eventId().toString());
			var publisher = new EligibilityOutboxPublisher(database, kafka, tx, 5);
			publisher.publishEvent(completed.eventId());
			AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
			await().atMost(Duration.ofSeconds(25)).until(() -> {
				for (var record : consumer.poll(Duration.ofMillis(250)))
					if (new ObjectMapper().readTree(record.value()).path("eventId").asText()
							.equals(completed.eventId().toString()))
						found.set(record);
				return found.get() != null;
			});
			assertThat(found.get().key()).isEqualTo(request.applicationId().toString());
			assertThat(found.get().value()).isEqualTo(payload);
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM event_outbox WHERE event_id=? AND published_at IS NOT NULL AND attempts=1",
					Integer.class, completed.eventId().toString())).isEqualTo(1);
			publisher.publishEvent(completed.eventId());
			assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE event_id=?", Integer.class,
					completed.eventId().toString())).isEqualTo(1);
			System.out.println(
					"ELIGIBILITY DELIVERY CHECK: committed Oracle result/outbox delivered to real test Kafka; original event ID and application key preserved");
		} finally {
			OracleEligibilityCompletionTest.cleanup(request.applicationId());
		}
	}
}
