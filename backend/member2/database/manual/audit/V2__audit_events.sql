CREATE TABLE audit_event (
 event_id VARCHAR2(36) PRIMARY KEY, application_id VARCHAR2(36) NOT NULL,
 event_type VARCHAR2(100) NOT NULL, correlation_id VARCHAR2(100) NOT NULL,
 producer VARCHAR2(100) NOT NULL, occurred_at VARCHAR2(40) NOT NULL, safe_payload CLOB NOT NULL
);
CREATE INDEX ix_audit_application ON audit_event(application_id);
