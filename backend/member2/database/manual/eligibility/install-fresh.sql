-- Run Script (F5) as CF_ELIGIBILITY in FREEPDB1. FRESH EMPTY SCHEMA ONLY.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
DECLARE n NUMBER; BEGIN
 IF USER <> 'CF_ELIGIBILITY' THEN RAISE_APPLICATION_ERROR(-20001,'Connect as CF_ELIGIBILITY'); END IF;
 SELECT COUNT(*) INTO n FROM user_tables;
 IF n > 0 THEN RAISE_APPLICATION_ERROR(-20002,'Schema is not empty. Use verify.sql; do not rerun the fresh installer.'); END IF;
END;
/
@@V1__event_delivery_tables.sql
@@V2__financial_storage.sql
@@V3__financial_summary_package.sql
@@V4__demo_product_rules.sql
@@V5__eligibility_results.sql
@@V6__financial_import_provenance.sql
@@V7__eligibility_completion_outbox.sql
@@V8__eligibility_operation_intake.sql
COMMIT;
