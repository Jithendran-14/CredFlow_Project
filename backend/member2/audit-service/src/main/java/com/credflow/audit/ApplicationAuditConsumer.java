package com.credflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name="credflow.audit.consumer-enabled",havingValue="true",matchIfMissing=true)
public class ApplicationAuditConsumer {
    private final DatabaseAccess database;
    private final ObjectMapper json;
    private final AuditEventProjector projector;
    public ApplicationAuditConsumer(DatabaseAccess database,ObjectMapper json) {
        this.database=database;this.json=json;this.projector=new AuditEventProjector(json);
    }
    @Transactional
    public void consume(String payload) throws Exception {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Audit writes require a database transaction");
        var safe=projector.project(payload);
        store(safe);
    }
    @Transactional
    @KafkaListener(topics="#{'${credflow.audit.topics:application.submitted}'.split(',')}")
    public void receive(org.apache.kafka.clients.consumer.ConsumerRecord<String,String> record) throws Exception {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Audit writes require a database transaction");
        var safe=projector.project(record.value());
        if(!record.topic().equals(safe.path("eventType").asText()) || !safe.path("applicationId").asText().equals(record.key()))
            throw new AuditEventProjector.InvalidAuditEventException("Kafka topic/key do not match event identity");
        store(safe);
    }
    private void store(com.fasterxml.jackson.databind.node.ObjectNode safe) throws Exception {
        String id=safe.path("eventId").asText();
        try {
            // Unique inbox key claims delivery atomically; simultaneous retries cannot both write history.
            database.update("INSERT INTO event_inbox(event_id) VALUES(?)",id);
        } catch(DuplicateKeyException duplicate) {
            return;
        }
        database.update("INSERT INTO audit_event(event_id,application_id,event_type,correlation_id,producer,occurred_at,safe_payload) VALUES(?,?,?,?,?,?,?)",
            id,safe.path("applicationId").asText(),safe.path("eventType").asText(),safe.path("correlationId").asText(),
            safe.path("producer").asText(),safe.path("occurredAt").asText(),json.writeValueAsString(safe));
    }
}
