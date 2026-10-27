-- Read only. Run as CF_ELIGIBILITY. Do not include private retrieval_reference in demo output.
SELECT operation_id,evaluation_request_id,application_id,application_revision,
 status,created_at,started_at,finished_at,eligibility_result_id,error_code,attempts
FROM eligibility_operation ORDER BY created_at DESC;
