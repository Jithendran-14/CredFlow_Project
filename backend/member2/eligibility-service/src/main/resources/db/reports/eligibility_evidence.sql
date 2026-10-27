-- Run as CF_ELIGIBILITY. Real test fixture rows are cleaned up after testing.
SELECT eligibility_result_id,evaluation_request_id,application_id,application_revision,
 financial_snapshot_id,verification_id,outcome,average_monthly_net,return_rate_percent,evaluated_at
FROM eligibility_evaluation ORDER BY evaluated_at DESC;
SELECT e.application_id,e.eligibility_result_id,l.product_id,l.lender_id,l.rule_version,
 l.eligible,l.indicative_max_amount,l.reason_codes
FROM eligibility_evaluation e JOIN eligibility_lender_result l
 ON l.eligibility_result_id=e.eligibility_result_id
ORDER BY e.evaluated_at DESC,l.routing_priority,l.product_id;
SELECT table_name FROM user_tables WHERE table_name IN ('ELIGIBILITY_EVALUATION','ELIGIBILITY_LENDER_RESULT');


-- Result references retain the provider dataset through immutable snapshot/import links.
SELECT e.eligibility_result_id,e.application_id,e.application_revision,e.verification_id,
 b.source_provider,b.source_reference AS financial_data_reference,
 b.source_data_version AS financial_data_version,b.source_retrieved_at
FROM eligibility_evaluation e JOIN financial_snapshot f ON e.financial_snapshot_id=f.snapshot_id
JOIN financial_import_batch b ON f.import_batch_id=b.import_batch_id
ORDER BY e.evaluated_at DESC;
