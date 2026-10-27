CREATE TABLE event_outbox (
 event_id VARCHAR2(36) PRIMARY KEY, application_id VARCHAR2(36) NOT NULL,
 event_type VARCHAR2(100) NOT NULL, payload CLOB NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE, attempts NUMBER(10) DEFAULT 0 NOT NULL
);
CREATE INDEX ix_outbox_pending ON event_outbox(published_at, created_at);
CREATE TABLE event_inbox (
 event_id VARCHAR2(36) PRIMARY KEY, processed_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
