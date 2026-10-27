package com.credflow.audit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
class WorkflowAuditProjectionTest {
 static final ObjectMapper JSON=new ObjectMapper();
 final AuditEventProjector projector=new AuditEventProjector(JSON);
 static Stream<String> events(){return new WorkflowAuditProjection(JSON).supported().stream();}
 static ObjectNode fixture(String type) {
  try(var in=WorkflowAuditProjectionTest.class.getResourceAsStream("/member3-events/"+type+".json")) {
   return (ObjectNode)JSON.readTree(in);
  }catch(Exception ex){throw new IllegalStateException(ex);}
 }
 @ParameterizedTest @MethodSource("events")
 void suppliedRealContractExampleProducesSafeHistory(String type)throws Exception {
  var event=fixture(type);event.put("accessToken","TOP_SECRET");
  ((ObjectNode)event.path("actor")).put("password","TOP_SECRET");
  var data=(ObjectNode)event.path("data");
  data.put("rawIdentityDocument","TOP_SECRET");data.put("bankAccountNumber","TOP_SECRET");
  data.putArray("answers").addObject().put("value","TOP_SECRET");
  data.putArray("syntheticDocumentReferences").add("TOP_SECRET");
  data.put("reasonText","TOP_SECRET");data.put("reason","TOP_SECRET");data.put("termsReference","TOP_SECRET");
  var safe=projector.project(event.toString());
  assertThat(safe.path("eventType").asText()).isEqualTo(type);
  assertThat(safe.path("eventId")).isEqualTo(event.path("eventId"));
  assertThat(safe.path("data").size()).isPositive();
  assertThat(safe.toString()).doesNotContain("TOP_SECRET","answers","syntheticDocumentReferences","rawIdentityDocument","bankAccountNumber","reasonText","termsReference");
 }
 @ParameterizedTest @MethodSource("events")
 void unrelatedProducerCannotImpersonateEventOwner(String type){
  var event=fixture(type);event.put("producer","unrelated-service");
  assertThatThrownBy(()->projector.project(event.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @ParameterizedTest @MethodSource("events")
 void missingRequiredBusinessReferenceIsRejected(String type)throws Exception {
  var event=fixture(type);
  try(var in=getClass().getResourceAsStream("/audit-events/"+type+".json")) {
   String field=JSON.readTree(in).path("required").get(0).asText();((ObjectNode)event.path("data")).remove(field);
  }
  assertThatThrownBy(()->projector.project(event.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @Test void eligibilityCannotReferToAnotherApplication(){
  var e=fixture("eligibility.completed");((ObjectNode)e.path("data")).put("applicationId",UUID.randomUUID().toString());
  assertThatThrownBy(()->projector.project(e.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @Test void invalidWorkflowStatusIsRejected(){
  var e=fixture("application.status.changed");((ObjectNode)e.path("data")).put("status","WHATEVER");
  assertThatThrownBy(()->projector.project(e.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @Test void nonUuidOfferIdentityIsRejected(){
  var e=fixture("loan.offer.accepted");((ObjectNode)e.path("data")).put("offerId","some-offer");
  assertThatThrownBy(()->projector.project(e.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @Test void freeTextCannotHideInEligibilityReasonCodes(){
  var e=fixture("eligibility.completed");((ObjectNode)e.path("data")).putArray("reasonCodes").add("seller private account details");
  assertThatThrownBy(()->projector.project(e.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
 @Test void unsupportedMember1VersionIsStillGated()throws Exception {
  var e=AuditTestMessages.event(JSON);e.put("schemaVersion","2.0");
  assertThatThrownBy(()->projector.project(e.toString())).isInstanceOf(AuditEventProjector.InvalidAuditEventException.class);
 }
}
