-- Execute as CF_AUDIT. Correlate IDs through reports/APIs, not cross-schema joins.
SELECT event_id, application_id, event_type, correlation_id, producer, occurred_at
FROM audit_event ORDER BY occurred_at, event_id;

SELECT event_id, processed_at FROM event_inbox ORDER BY processed_at;
