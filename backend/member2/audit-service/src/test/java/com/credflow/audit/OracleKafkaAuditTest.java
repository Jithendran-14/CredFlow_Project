package com.credflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Kafka transport and Oracle; not the complete application/outbox workflow. */
@SpringBootTest(properties={
 "credflow.audit.topics=application.submitted",
 "spring.kafka.consumer.group-id=member2-audit-broker-proof",
 "spring.kafka.consumer.auto-offset-reset=earliest",
 "spring.kafka.listener.ack-mode=record",
 "credflow.audit.consumer-enabled=true",
 "logging.level.org.apache.kafka=ERROR",
 "logging.level.kafka=ERROR",
 "logging.level.state.change.logger=ERROR"})
@EmbeddedKafka(partitions=1,kraft=true,topics={"application.submitted","application.submitted.DLT"},
 bootstrapServersProperty="spring.kafka.bootstrap-servers")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="ORACLE_KAFKA_TESTS",matches="true")
class OracleKafkaAuditTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired KafkaTemplate<String,String> kafka;
 @Autowired EmbeddedKafkaBroker broker;
 @Autowired KafkaListenerEndpointRegistry listeners;
 @Autowired ObjectMapper json;
 @MockitoBean JwtDecoder decoder;
 @BeforeEach void assigned() {
  for(var container:listeners.getListenerContainers()) ContainerTestUtils.waitForAssignment(container,1);
 }
 int count(String table,String id) {
  return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE event_id=?",Integer.class,id);
 }
 void cleanup(String id) {
  jdbc.update("DELETE FROM audit_event WHERE event_id=?",id);
  jdbc.update("DELETE FROM event_inbox WHERE event_id=?",id);
 }
 void waitForCommit(long offset) {
  try(var admin=AdminClient.create(Map.of("bootstrap.servers",broker.getBrokersAsString()))) {
   await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
    var offsets=admin.listConsumerGroupOffsets("member2-audit-broker-proof").partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
    var committed=offsets.get(new TopicPartition("application.submitted",0));
    assertThat(committed).isNotNull();assertThat(committed.offset()).isGreaterThanOrEqualTo(offset);
   });
  }
 }
 @Test void realKafkaDeliveryAndRetryStoreOneOracleHistoryRecord() throws Exception {
  String payload=AuditTestMessages.unique(json),id=json.readTree(payload).path("eventId").asText();
  String app=json.readTree(payload).path("applicationId").asText();
  try {
   kafka.send("application.submitted",app,payload).get(15,TimeUnit.SECONDS);
   var second=kafka.send("application.submitted",app,payload).get(15,TimeUnit.SECONDS);
   waitForCommit(second.getRecordMetadata().offset()+1);
   assertThat(count("audit_event",id)).isEqualTo(1);assertThat(count("event_inbox",id)).isEqualTo(1);
   var saved=json.readTree(jdbc.queryForObject("SELECT safe_payload FROM audit_event WHERE event_id=?",String.class,id));
   assertThat(saved.path("applicationId").asText()).isEqualTo(app);
   System.out.println("KAFKA TO ORACLE CHECK: two Kafka deliveries consumed and committed; one Audit history record saved");
  } finally {cleanup(id);}
 }
 @Test void malformedMessageReachesDeadLetterTopicWithoutSavingHistory() throws Exception {
  var event=AuditTestMessages.event(json);String id=UUID.randomUUID().toString();event.put("eventId",id);event.remove("actor");
  String payload=json.writeValueAsString(event);
  Map<String,Object> props=new HashMap<>();
  props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBrokersAsString());
  props.put(ConsumerConfig.GROUP_ID_CONFIG,"dlt-check-"+UUID.randomUUID());
  props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
  props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
  props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
  try(var dlt=new KafkaConsumer<String,String>(props)) {
   broker.consumeFromAnEmbeddedTopic(dlt,"application.submitted.DLT");
   var sent=kafka.send("application.submitted",event.path("applicationId").asText(),payload).get(15,TimeUnit.SECONDS);
   AtomicReference<String> found=new AtomicReference<>();
   await().atMost(Duration.ofSeconds(30)).until(()->{
    for(var record:dlt.poll(Duration.ofMillis(250))) if(record.value().contains(id)) found.set(record.value());
    return found.get()!=null;
   });
   assertThat(found.get()).isEqualTo(payload);waitForCommit(sent.getRecordMetadata().offset()+1);
   assertThat(count("audit_event",id)).isZero();assertThat(count("event_inbox",id)).isZero();
   System.out.println("KAFKA DLT CHECK: malformed event recovered to the dead-letter topic; no Oracle history was created");
  } finally {cleanup(id);}
 }
}
