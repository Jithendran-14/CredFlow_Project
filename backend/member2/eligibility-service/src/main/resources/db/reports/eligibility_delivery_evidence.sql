-- Run as CF_ELIGIBILITY. Read only; test fixture rows are cleaned after demonstration.
SELECT c.eligibility_result_id,o.event_id,o.application_id,o.event_type,
 o.created_at,o.published_at,o.attempts
FROM eligibility_completion c JOIN event_outbox o ON o.event_id=c.event_id
ORDER BY o.created_at DESC;

-- Unpublished includes messages waiting for their first send and failed sends.
SELECT event_id,application_id,attempts,created_at FROM event_outbox
WHERE event_type='eligibility.completed' AND published_at IS NULL
ORDER BY created_at;

-- Default attempt limit is five. Adjust this read-only filter if the setting changes.
SELECT event_id,application_id,attempts,created_at FROM event_outbox
WHERE event_type='eligibility.completed' AND published_at IS NULL AND attempts>=5
ORDER BY created_at;
