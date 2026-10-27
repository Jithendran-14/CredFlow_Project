CREATE TABLE eligibility_evaluation (
 eligibility_result_id VARCHAR2(36) PRIMARY KEY,
 evaluation_request_id VARCHAR2(36) NOT NULL UNIQUE,
 application_id VARCHAR2(36) NOT NULL,
 application_revision NUMBER(10) NOT NULL CHECK(application_revision > 0),
 financial_snapshot_id VARCHAR2(36) NOT NULL REFERENCES financial_snapshot(snapshot_id),
 verification_id VARCHAR2(36) NOT NULL,
 request_hash VARCHAR2(64) NOT NULL,
 calculation_version VARCHAR2(20) NOT NULL,
 requested_amount NUMBER(19,2) NOT NULL CHECK(requested_amount > 0),
 currency VARCHAR2(3) NOT NULL CHECK(currency='INR'),
 loan_purpose VARCHAR2(40) NOT NULL,
 business_start_date DATE NOT NULL,
 as_of_date DATE NOT NULL,
 outcome VARCHAR2(20) NOT NULL CHECK(outcome IN ('EVALUATED','ACTION_REQUIRED')),
 reason_codes CLOB NOT NULL CHECK(reason_codes IS JSON),
 average_monthly_net NUMBER(19,2), average_monthly_payout NUMBER(19,2),
 return_rate_percent NUMBER(7,2),
 evaluated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 CHECK((outcome='ACTION_REQUIRED' AND average_monthly_net IS NULL
   AND average_monthly_payout IS NULL AND return_rate_percent IS NULL)
  OR (outcome='EVALUATED' AND average_monthly_net IS NOT NULL
   AND average_monthly_payout IS NOT NULL AND return_rate_percent IS NOT NULL))
);
CREATE INDEX ix_eval_application ON eligibility_evaluation(application_id,application_revision,evaluated_at);
CREATE TABLE eligibility_lender_result (
 eligibility_result_id VARCHAR2(36) NOT NULL REFERENCES eligibility_evaluation(eligibility_result_id),
 product_id VARCHAR2(36) NOT NULL,
 lender_id VARCHAR2(36) NOT NULL,
 rule_version NUMBER(10) NOT NULL,
 indicative_max_amount NUMBER(19,2) NOT NULL CHECK(indicative_max_amount >= 0),
 eligible NUMBER(1) NOT NULL CHECK(eligible IN (0,1)),
 reason_codes CLOB NOT NULL CHECK(reason_codes IS JSON),
 routing_priority NUMBER(5) NOT NULL,
 PRIMARY KEY(eligibility_result_id,product_id),
 FOREIGN KEY(product_id,rule_version) REFERENCES lender_product_rule(product_id,rule_version)
);
