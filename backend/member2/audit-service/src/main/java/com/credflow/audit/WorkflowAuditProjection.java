package com.credflow.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Audit-specific field policy. This checks the history fields, not every upstream business rule. */
final class WorkflowAuditProjection {
 private final ObjectMapper json;
 private final Map<String,JsonNode> policies=new LinkedHashMap<>();
 WorkflowAuditProjection(ObjectMapper json) {
  this.json=json;
  try {
   for(JsonNode name:load("catalog"))policies.put(name.asText(),load(name.asText()));
  }catch(Exception ex){throw new IllegalStateException("Audit event policy is invalid",ex);}
 }
 private JsonNode load(String name)throws Exception {
  try(InputStream in=getClass().getResourceAsStream("/audit-events/"+name+".json")) {
   if(in==null)throw new IllegalStateException("Missing Audit policy: "+name);return json.readTree(in);
  }
 }
 Set<String> supported(){return Collections.unmodifiableSet(policies.keySet());}
 String producer(String event) {
  JsonNode policy=policies.get(event);if(policy==null)throw invalid("Unsupported eventType");return policy.path("producer").asText();
 }
 ObjectNode data(String event,JsonNode data,String applicationId) {
  if(!data.isObject())throw invalid("data must be an object");
  JsonNode policy=policies.get(event);
  for(JsonNode required:policy.path("required"))if(!data.hasNonNull(required.asText()))throw invalid("Missing "+required.asText());
  if(data.has("applicationId") && !applicationId.equals(data.path("applicationId").asText()))throw invalid("Application identity mismatch");
  // Even when omitted from history, required collection fields must have the expected outer type.
  for(String array:List.of("questions","lenderResults"))if(data.has(array) && !data.path(array).isArray())throw invalid(array+" must be an array");
  ObjectNode safe=json.createObjectNode();
  policy.path("fields").fields().forEachRemaining(field->{
   String key=field.getKey();if(data.has(key)) {
    JsonNode value=data.get(key);if(!valid(value,field.getValue()))throw invalid("Invalid "+key);
    if(key.equals("reasonCodes")) {
     if(!value.isArray() || value.size()>100)throw invalid("Invalid reasonCodes");
     for(JsonNode reason:value)if(!reason.isTextual() || !reason.asText().matches("[A-Z][A-Z0-9_]{0,99}"))throw invalid("Invalid reason code");
    }
    safe.set(key,value.deepCopy());
   }
  });
  // Eligibility's business application ID is required to agree with the envelope.
  if(event.equals("eligibility.completed") && data.path("outcome").asText().equals("ACTION_REQUIRED") && !data.path("lenderResults").isEmpty())throw invalid("Action-required event cannot contain lender results");
  return safe;
 }
 private boolean valid(JsonNode value,JsonNode rule) {
  if(rule.has("oneOf")){int matches=0;for(JsonNode child:rule.get("oneOf"))if(valid(value,child))matches++;if(matches!=1)return false;}
  if(rule.has("anyOf")){boolean match=false;for(JsonNode child:rule.get("anyOf"))match|=valid(value,child);if(!match)return false;}
  if(rule.has("const") && !rule.get("const").equals(value))return false;
  if(rule.has("enum")){boolean match=false;for(JsonNode option:rule.get("enum"))match|=option.equals(value);if(!match)return false;}
  if(rule.has("type")) {
   String type=rule.path("type").asText();
   boolean ok=switch(type){case "string"->value.isTextual();case "integer"->value.isIntegralNumber() && value.canConvertToLong();case "number"->value.isNumber();case "boolean"->value.isBoolean();case "array"->value.isArray();case "object"->value.isObject();case "null"->value.isNull();default->false;};
   if(!ok)return false;
  }
  if(value.isTextual()) {
   String text=value.asText();if(text.length()>Math.min(rule.path("maxLength").asInt(1000),1000) || text.length()<rule.path("minLength").asInt(0))return false;
   if(rule.has("pattern") && !java.util.regex.Pattern.compile(rule.path("pattern").asText()).matcher(text).find())return false;
   try {
    switch(rule.path("format").asText()) {
     case "uuid"->{if(!UUID.fromString(text).toString().equalsIgnoreCase(text))return false;}
     case "date"->LocalDate.parse(text);
     case "date-time"->OffsetDateTime.parse(text);
    }
   }catch(IllegalArgumentException|java.time.DateTimeException ex){return false;}
  }
  if(value.isNumber()) {
   BigDecimal number=value.decimalValue();if(rule.has("minimum") && number.compareTo(rule.get("minimum").decimalValue())<0)return false;
   if(rule.has("maximum") && number.compareTo(rule.get("maximum").decimalValue())>0)return false;
  }
  if(value.isArray()) {
   if(value.size()>100 || value.size()<rule.path("minItems").asInt(0) || value.size()>rule.path("maxItems").asInt(100))return false;
   if(rule.has("items"))for(JsonNode item:value)if(!valid(item,rule.get("items")))return false;
  }
  return true;
 }
 private AuditEventProjector.InvalidAuditEventException invalid(String message){return new AuditEventProjector.InvalidAuditEventException(message);}
}
