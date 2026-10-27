-- Run while connected as CF_ELIGIBILITY. Read-only queries.
SELECT object_name, object_type, status FROM user_objects
WHERE object_name='CF_FINANCIAL_SUMMARY_PKG';
SELECT name, type, line, position, text FROM user_errors
WHERE name='CF_FINANCIAL_SUMMARY_PKG' ORDER BY sequence;
SELECT product_name,rule_version,min_age_months,min_average_net,max_return_percent,
 min_amount,max_amount,sales_multiplier,routing_priority FROM lender_product_rule ORDER BY routing_priority;
SELECT snapshot_id,application_id,application_revision,as_of_date,coverage_status,outcome,reason_code,
 total_gross,total_returns,ROUND((total_gross-total_returns)/3,2) average_monthly_net,
 ROUND(total_payout/3,2) average_monthly_payout FROM financial_snapshot ORDER BY created_at DESC;

SELECT import_batch_id,application_id,application_revision,as_of_date,source_provider,
 source_reference,source_data_version,source_retrieved_at FROM financial_import_batch ORDER BY imported_at DESC;
