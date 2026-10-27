package com.credflow.eligibility;

import com.credflow.eligibility.api.EligibilityResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stages the Member 2 draft event. Shared publication is disabled until contracts are agreed. */
@Service
public class EligibilityCompletionOutbox {
    public record Actor(String type, String subject, UUID organisationId) {}
    public record Metadata(String correlationId, long aggregateVersion, Actor actor, Actor originActor, UUID causationId) {}
    private final DatabaseAccess database;
    private final ObjectMapper json;
    public EligibilityCompletionOutbox(DatabaseAccess database,ObjectMapper json) { this.database=database;this.json=json; }
    @Transactional
    public UUID record(EligibilityEvaluationService.StoredEvaluation result,Metadata metadata) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Completion outbox requires a database transaction");
        Objects.requireNonNull(result);Objects.requireNonNull(metadata);
        if(metadata.correlationId()==null || !metadata.correlationId().matches("[A-Za-z0-9._:-]{1,100}")
                || metadata.aggregateVersion()<1) throw new IllegalArgumentException("Invalid event metadata");
        validateActor(metadata.actor());if(metadata.originActor()!=null)validateActor(metadata.originActor());
        // Serialize creation even when two callers stage the same saved result.
        String storedApplication=database.queryForObject("SELECT application_id FROM eligibility_evaluation WHERE eligibility_result_id=? FOR UPDATE",
            String.class,result.eligibilityResultId().toString());
        if(!result.applicationId().toString().equals(storedApplication)) throw new IllegalArgumentException("Result identity mismatch");
        var existing=database.queryForList("SELECT event_id FROM eligibility_completion WHERE eligibility_result_id=?",
            String.class,result.eligibilityResultId().toString());
        if(!existing.isEmpty())return UUID.fromString(existing.getFirst());
        UUID id=UUID.randomUUID();
        var e=json.createObjectNode();e.put("eventId",id.toString());e.put("eventType","eligibility.completed");
        e.put("schemaVersion","1.0");e.put("occurredAt",result.evaluatedAt().toInstant().toString());
        e.put("producer","eligibility-service");e.put("applicationId",result.applicationId().toString());
        e.put("aggregateVersion",metadata.aggregateVersion());e.put("correlationId",metadata.correlationId());
        e.set("actor",actor(metadata.actor()));
        if(metadata.originActor()!=null)e.set("originActor",actor(metadata.originActor()));
        if(metadata.causationId()!=null)e.put("causationId",metadata.causationId().toString());
        e.set("data",json.valueToTree(EligibilityResultResponse.from(result)));
        database.update("INSERT INTO event_outbox(event_id,application_id,event_type,payload) VALUES(?,?,?,?)",
            id.toString(),result.applicationId().toString(),"eligibility.completed",e.toString());
        database.update("INSERT INTO eligibility_completion(eligibility_result_id,event_id) VALUES(?,?)",
            result.eligibilityResultId().toString(),id.toString());
        return id;
    }
    private void validateActor(Actor a) {
        if(a==null || a.type()==null || !Set.of("HUMAN","SYSTEM_CLIENT","SYSTEM").contains(a.type())
                || a.subject()==null || a.subject().isBlank() || a.subject().length()>1000)
            throw new IllegalArgumentException("Invalid trusted actor");
    }
    private com.fasterxml.jackson.databind.node.ObjectNode actor(Actor a) {
        var value=json.createObjectNode();value.put("type",a.type());value.put("subject",a.subject());
        if(a.organisationId()!=null)value.put("organisationId",a.organisationId().toString());return value;
    }
}
