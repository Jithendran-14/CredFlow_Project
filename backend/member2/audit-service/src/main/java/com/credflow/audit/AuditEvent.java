package com.credflow.audit;
import jakarta.persistence.*;
@Entity @Table(name="audit_event")
public class AuditEvent {
 @Id @Column(name="event_id",length=36) private String eventId;
 @Column(name="application_id",length=36,nullable=false) private String applicationId;
 @Column(name="event_type",length=100,nullable=false) private String eventType;
 @Column(name="correlation_id",length=100,nullable=false) private String correlationId;
 @Column(name="producer",length=100,nullable=false) private String producer;
 @Column(name="occurred_at",length=40,nullable=false) private String occurredAt;
 @Lob @Column(name="safe_payload",nullable=false) private String safePayload;
 protected AuditEvent() {}
 public String getSafePayload(){return safePayload;}
}
