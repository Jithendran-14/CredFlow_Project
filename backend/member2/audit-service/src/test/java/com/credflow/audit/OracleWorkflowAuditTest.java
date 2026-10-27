package com.credflow.audit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties={"credflow.audit.topics=application.status.changed,eligibility.completed,lender.decision.received,loan.offer.accepted",
 "spring.kafka.consumer.group-id=member2-workflow-history-proof","spring.kafka.listener.ack-mode=record",
 "logging.level.org.apache.kafka=ERROR","logging.level.kafka=ERROR","logging.level.state.change.logger=ERROR"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions=1,kraft=true,topics={"application.status.changed","eligibility.completed","lender.decision.received","loan.offer.accepted",
 "application.status.changed.DLT","eligibility.completed.DLT","lender.decision.received.DLT","loan.offer.accepted.DLT"},bootstrapServersProperty="spring.kafka.bootstrap-servers")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="ORACLE_KAFKA_TESTS",matches="true")
class OracleWorkflowAuditTest {
 @Autowired JdbcTemplate database;@Autowired KafkaTemplate<String,String> kafka;
 @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker;
 @Autowired KafkaListenerEndpointRegistry listeners;@Autowired ApplicationAuditConsumer consumer;
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;
 @MockitoBean JwtDecoder decoder;
 final List<String> ids=new ArrayList<>();
 @BeforeEach void assigned(){for(var container:listeners.getListenerContainers())ContainerTestUtils.waitForAssignment(container,4);}
 @AfterEach void cleanup(){for(String id:ids){database.update("DELETE FROM audit_event WHERE event_id=?",id);database.update("DELETE FROM event_inbox WHERE event_id=?",id);}}
 ObjectNode event(String type,String app,int second){
  var e=WorkflowAuditProjectionTest.fixture(type);String id=UUID.randomUUID().toString();ids.add(id);
  e.put("eventId",id);e.put("applicationId",app);e.put("occurredAt","2026-10-17T10:00:0"+second+"Z");
  if(e.path("data").has("applicationId"))((ObjectNode)e.path("data")).put("applicationId",app);
  ((ObjectNode)e.path("data")).putArray("answers").addObject().put("value","TOP_SECRET");return e;
 }
 @Test void realKafkaStoresOrderedSafeTimelineAndOneRowPerEvent()throws Exception {
  String app=UUID.randomUUID().toString();
  String[] types={"application.status.changed","eligibility.completed","lender.decision.received","loan.offer.accepted"};
  var expectedOffsets=new HashMap<org.apache.kafka.common.TopicPartition,Long>();
  for(int i=0;i<types.length;i++){
   var e=event(types[i],app,i);kafka.send(types[i],app,e.toString()).get(15,TimeUnit.SECONDS);
   var duplicate=kafka.send(types[i],app,e.toString()).get(15,TimeUnit.SECONDS);
   expectedOffsets.put(new org.apache.kafka.common.TopicPartition(types[i],0),duplicate.getRecordMetadata().offset()+1);
  }
  try(var admin=org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers",broker.getBrokersAsString()))) {
   await().atMost(Duration.ofSeconds(35)).untilAsserted(()->{
    var committed=admin.listConsumerGroupOffsets("member2-workflow-history-proof").partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
    expectedOffsets.forEach((partition,offset)->{assertThat(committed.get(partition)).isNotNull();assertThat(committed.get(partition).offset()).isGreaterThanOrEqualTo(offset);});
   });
  }
  await().atMost(Duration.ofSeconds(35)).untilAsserted(()->assertThat(database.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?",Integer.class,app)).isEqualTo(4));
  var result=mvc.perform(get("/api/v1/applications/"+app+"/audit").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4)).andReturn().getResponse().getContentAsString();
  var rows=json.readTree(result);for(int i=0;i<types.length;i++)assertThat(rows.get(i).path("eventType").asText()).isEqualTo(types[i]);
  assertThat(result).doesNotContain("TOP_SECRET","answers");
  assertThat(database.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE event_id IN (SELECT event_id FROM audit_event WHERE application_id=?)",Integer.class,app)).isEqualTo(4);
  mvc.perform(get("/api/v1/applications/"+app+"/audit").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SELLER"))))
   .andExpect(status().isForbidden());
 }
 @Test void forgedKafkaTopicAndKeyDoNotCreateHistory(){
  var e=event("loan.offer.accepted",UUID.randomUUID().toString(),1);
  var record=new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("lender.decision.received",0,0L,e.path("applicationId").asText(),e.toString());
  assertThatThrownBy(()->consumer.receive(record)).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
  var wrongKey=new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("loan.offer.accepted",0,0L,UUID.randomUUID().toString(),e.toString());
  assertThatThrownBy(()->consumer.receive(wrongKey)).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
  assertThat(database.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE event_id=?",Integer.class,e.path("eventId").asText())).isZero();
 }
}
