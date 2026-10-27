package com.credflow.audit;
import java.util.List;
import com.fasterxml.jackson.databind.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController public class AuditController {
 private final AuditEventRepository events;private final ObjectMapper json;
 public AuditController(AuditEventRepository events,ObjectMapper json){this.events=events;this.json=json;}
 @GetMapping("/api/v1/applications/{id}/audit") @PreAuthorize("hasRole('ADMIN')") @Transactional(readOnly=true)
 public List<JsonNode> read(@PathVariable String id){return events.findByApplicationIdOrderByOccurredAtAscEventIdAsc(id).stream().map(event->{
  try{return json.readTree(event.getSafePayload());}catch(Exception ex){throw new IllegalStateException("Stored audit event is invalid",ex);}
 }).toList();}
}
