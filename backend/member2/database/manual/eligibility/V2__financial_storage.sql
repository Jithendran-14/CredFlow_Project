CREATE TABLE financial_import_batch (
 import_batch_id VARCHAR2(36) PRIMARY KEY,
 application_id VARCHAR2(36) NOT NULL,
 application_revision NUMBER(10) NOT NULL CHECK (application_revision > 0),
 as_of_date DATE NOT NULL,
 currency VARCHAR2(3) DEFAULT 'INR' NOT NULL CHECK (currency = 'INR'),
 source_reference VARCHAR2(200) NOT NULL,
 imported_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ix_fin_import ON financial_import_batch(application_id, application_revision, as_of_date);
CREATE TABLE financial_source_month (
 import_batch_id VARCHAR2(36) NOT NULL REFERENCES financial_import_batch(import_batch_id),
 month_start DATE NOT NULL CHECK (month_start = TRUNC(month_start, 'MM')),
 coverage_status VARCHAR2(10) NOT NULL CHECK (coverage_status IN ('COMPLETE','INCOMPLETE')),
 gross_sales NUMBER(19,2), returns_amount NUMBER(19,2), payout_amount NUMBER(19,2),
 order_count NUMBER(10), returned_order_count NUMBER(10),
 PRIMARY KEY(import_batch_id, month_start),
 CHECK (gross_sales >= 0 AND returns_amount >= 0 AND payout_amount >= 0),
 CHECK (order_count >= 0 AND returned_order_count >= 0),
 CHECK (coverage_status <> 'COMPLETE' OR
  (gross_sales IS NOT NULL AND returns_amount IS NOT NULL AND payout_amount IS NOT NULL
   AND order_count IS NOT NULL AND returned_order_count IS NOT NULL))
);
CREATE TABLE financial_snapshot (
 snapshot_id VARCHAR2(36) PRIMARY KEY,
 import_batch_id VARCHAR2(36) NOT NULL REFERENCES financial_import_batch(import_batch_id),
 application_id VARCHAR2(36) NOT NULL, application_revision NUMBER(10) NOT NULL,
 as_of_date DATE NOT NULL,
 coverage_status VARCHAR2(10) NOT NULL,
 outcome VARCHAR2(20) NOT NULL CHECK(outcome IN ('EVALUATED','ACTION_REQUIRED')),
 reason_code VARCHAR2(80),
 total_gross NUMBER(21,2) NOT NULL, total_returns NUMBER(21,2) NOT NULL,
 total_payout NUMBER(21,2) NOT NULL,
 total_orders NUMBER(12) NOT NULL, total_returned_orders NUMBER(12) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 UNIQUE(import_batch_id)
);
CREATE INDEX ix_fin_snapshot ON financial_snapshot(application_id, application_revision);
CREATE TABLE financial_snapshot_month (
 snapshot_id VARCHAR2(36) NOT NULL REFERENCES financial_snapshot(snapshot_id),
 month_start DATE NOT NULL,
 coverage_status VARCHAR2(10) NOT NULL,
 gross_sales NUMBER(19,2), returns_amount NUMBER(19,2), payout_amount NUMBER(19,2),
 order_count NUMBER(10), returned_order_count NUMBER(10),
 PRIMARY KEY(snapshot_id,month_start)
);
CREATE TABLE lender_product_rule (
 product_id VARCHAR2(36) NOT NULL, rule_version NUMBER(10) NOT NULL,
 lender_id VARCHAR2(36) NOT NULL, product_name VARCHAR2(100) NOT NULL,
 min_age_months NUMBER(5) NOT NULL CHECK(min_age_months >= 0),
 min_average_net NUMBER(19,2) NOT NULL CHECK(min_average_net >= 0),
 max_return_percent NUMBER(7,4) NOT NULL CHECK(max_return_percent BETWEEN 0 AND 100),
 min_amount NUMBER(19,2) NOT NULL CHECK(min_amount > 0),
 max_amount NUMBER(19,2) NOT NULL,
 sales_multiplier NUMBER(12,4) NOT NULL CHECK(sales_multiplier > 0),
 routing_priority NUMBER(5) NOT NULL,
 active NUMBER(1) DEFAULT 1 NOT NULL CHECK(active IN (0,1)),
 PRIMARY KEY(product_id,rule_version), CHECK(max_amount >= min_amount)
);
CREATE TABLE lender_product_purpose (
 product_id VARCHAR2(36) NOT NULL, rule_version NUMBER(10) NOT NULL,
 purpose VARCHAR2(40) NOT NULL,
 PRIMARY KEY(product_id,rule_version,purpose),
 FOREIGN KEY(product_id,rule_version) REFERENCES lender_product_rule(product_id,rule_version)
);
