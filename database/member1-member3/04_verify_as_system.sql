SET ECHO OFF
SET VERIFY OFF
SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR EXIT FAILURE

BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'SYSTEM' THEN
    RAISE_APPLICATION_ERROR(-20001, 'Connect as SYSTEM to the application PDB first.');
  END IF;
  IF SYS_CONTEXT('USERENV', 'CON_NAME') IN ('CDB$ROOT', 'PDB$SEED') THEN
    RAISE_APPLICATION_ERROR(-20002, 'Reconnect directly to the application PDB service, not the root/seed.');
  END IF;
END;
/

SET LINESIZE 240
SET PAGESIZE 100
PROMPT COLUMN MISMATCHES: this query must return no rows.
WITH expected AS (
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CONSENT_GRANTED' column_name, 'NUMBER' data_type, 1 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CONSENT_VERSION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'DISPATCH_AUTHORIZED' column_name, 'NUMBER' data_type, 1 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'EVALUATION_AS_OF_DATE' column_name, 'DATE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'EXPECTED_REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'LAST_REFRESH_REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'REVISION_PENDING' column_name, 'NUMBER' data_type, 1 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'LAST_INTEGRATION_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'UPDATED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'WORKFLOW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CANCELLATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CONSENT_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ELIGIBILITY_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ELIGIBILITY_OPERATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'EVALUATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'MARKETPLACE_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'REVISION_OPERATION' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'SNAPSHOT_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'VERIFICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'MARKETPLACE_SELLER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'SELLER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'AUTHORISED_RETRIEVAL_REFERENCE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CURRENCY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'DETAIL' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ELIGIBILITY_STATUS_URL' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'HEALTH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'LEGAL_NAME' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'PRODUCT_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'PURPOSE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'SELLER_SUBJECT' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'CONSENT_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'DECISION_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ELIGIBILITY_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'HEALTH_ISSUES_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'ORIGIN_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW' table_name, 'PROFILE_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'CREATED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'CANCELLATION_ACKNOWLEDGEMENT' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'EXTERNAL_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'PRODUCT_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_SUBMISSION' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'FEES' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'RATE' column_name, 'NUMBER' data_type, 9 precision_no, 4 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'TENURE' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'ACCEPTED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'EXPIRES_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'ACCEPTANCE_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'ACKNOWLEDGEMENT' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'CURRENCY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'FREQUENCY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'INTEREST_TYPE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_OFFER' table_name, 'TERMS_REFERENCE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'ANSWERED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'CREATED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'ACKNOWLEDGEMENT' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'ANSWERS_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'DOCUMENTS_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_INFORMATION_REQUEST' table_name, 'QUESTIONS_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW_IDEMPOTENCY' table_name, 'HTTP_STATUS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW_IDEMPOTENCY' table_name, 'ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW_IDEMPOTENCY' table_name, 'REQUEST_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_WORKFLOW_IDEMPOTENCY' table_name, 'RESPONSE_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'CREATED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'LEASE_UNTIL' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'POSITION_NO' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'KIND' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'LEASE_TOKEN' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'DELIVERY_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'LAST_ERROR' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'TARGET_NAME' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_ORCHESTRATION' owner, 'CF_M3_DELIVERY' table_name, 'PAYLOAD_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'APPLICATION_REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'CANCELLATION_ACKNOWLEDGED' column_name, 'NUMBER' data_type, 1 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'CANCELLATION_REQUESTED' column_name, 'NUMBER' data_type, 1 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'CALLBACK_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'EVENT_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'LEASE_UNTIL' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'SUBMITTED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'LEASE_TOKEN' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'EXTERNAL_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_DISPATCH' table_name, 'REQUEST_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'POSITION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'LEASE_TOKEN' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'TASK_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'OPERATION' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'FAILURE_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_TASK' table_name, 'PAYLOAD_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'LENDER_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'RECEIVED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'EVENT_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'CORRELATION_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'FAILURE_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'ACTOR_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_INT_CALLBACK' table_name, 'CALLBACK_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'CREATED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'LEASE_UNTIL' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'POSITION_NO' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'KIND' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'LEASE_TOKEN' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'DELIVERY_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'LAST_ERROR' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'TARGET_NAME' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_LENDER_INTEGRATION' owner, 'CF_M3_DELIVERY' table_name, 'PAYLOAD_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'APPLICATION_REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'OFFER_FEES' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'OFFER_RATE' column_name, 'NUMBER' data_type, 9 precision_no, 4 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'OFFERED_AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'REQUESTED_AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'ACCEPTED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'CALLBACK_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'LENDER_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'EXTERNAL_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'CASE_KEY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CASE' table_name, 'STATE_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'CALLBACK_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'EVENT_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'LEASE_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'LENDER_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'FAILURE_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'CASE_KEY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_BANK_A' owner, 'CF_SIM_CALLBACK' table_name, 'PAYLOAD_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'APPLICATION_REVISION' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'OFFER_FEES' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'OFFER_RATE' column_name, 'NUMBER' data_type, 9 precision_no, 4 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'OFFERED_AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'REQUESTED_AMOUNT' column_name, 'NUMBER' data_type, 19 precision_no, 2 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'ACCEPTED_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'CALLBACK_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'APPLICATION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'SUBMISSION_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'LENDER_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'PAYLOAD_HASH' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'EXTERNAL_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'CASE_KEY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CASE' table_name, 'STATE_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'ATTEMPTS' column_name, 'NUMBER' data_type, 10 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'CALLBACK_SEQUENCE' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'NEXT_ATTEMPT_AT' column_name, 'TIMESTAMP(6) WITH TIME ZONE' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'ROW_VERSION' column_name, 'NUMBER' data_type, 19 precision_no, 0 scale_no, NULL length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'EVENT_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'LEASE_ID' column_name, 'RAW' data_type, NULL precision_no, NULL scale_no, 16 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'STATUS' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'LENDER_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'FAILURE_CODE' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'Y' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'LENDER_ID' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'CASE_KEY' column_name, 'VARCHAR2' data_type, NULL precision_no, NULL scale_no, 2 length_no, 'N' nullable FROM DUAL
  UNION ALL
  SELECT 'CF_SIM_NBFC_B' owner, 'CF_SIM_CALLBACK' table_name, 'PAYLOAD_JSON' column_name, 'CLOB' data_type, NULL precision_no, NULL scale_no, NULL length_no, 'N' nullable FROM DUAL
)
SELECT e.owner, e.table_name, e.column_name,
       e.data_type expected_type, c.data_type actual_type,
       e.nullable expected_nullable, c.nullable actual_nullable
FROM expected e LEFT JOIN DBA_TAB_COLUMNS c
  ON c.owner=e.owner AND c.table_name=e.table_name AND c.column_name=e.column_name
WHERE c.column_name IS NULL OR c.data_type<>e.data_type OR c.nullable<>e.nullable
   OR (e.data_type='NUMBER' AND
       (NVL(c.data_precision,-1)<>e.precision_no OR NVL(c.data_scale,-1)<>e.scale_no))
   OR (e.data_type='VARCHAR2' AND (c.char_length<>e.length_no OR c.char_used<>'C'))
   OR (e.data_type='RAW' AND c.data_length<>e.length_no)
ORDER BY e.owner,e.table_name,e.column_name;

PROMPT TABLE TOTALS: Orchestration=6, Integration=4, Bank A=2, NBFC B=2.
SELECT owner, COUNT(*) table_count FROM DBA_TABLES
WHERE owner IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
GROUP BY owner ORDER BY owner;

PROMPT PRIMARY KEYS: 14 rows, all ENABLED / VALIDATED.
SELECT owner,table_name,constraint_name,status,validated FROM DBA_CONSTRAINTS
WHERE owner IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
  AND constraint_type='P' ORDER BY owner,table_name;

PROMPT BUSINESS UNIQUENESS: 4 rows, all ENABLED / VALIDATED.
SELECT owner,table_name,constraint_name,status,validated FROM DBA_CONSTRAINTS
WHERE owner IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
  AND constraint_type='U' ORDER BY owner,table_name;

PROMPT SEQUENCES: exactly 2 rows, INCREMENT_BY=1 and CYCLE_FLAG=N.
SELECT sequence_owner,sequence_name,increment_by,cycle_flag,cache_size FROM DBA_SEQUENCES
WHERE sequence_owner IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
ORDER BY sequence_owner,sequence_name;

PROMPT USERS: 4 rows; ACCOUNT_STATUS must allow login.
SELECT username,account_status,default_tablespace,temporary_tablespace FROM DBA_USERS
WHERE username IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
ORDER BY username;

PROMPT PRIVILEGES: each runtime user should have CREATE SESSION only.
SELECT grantee,privilege,admin_option FROM DBA_SYS_PRIVS
WHERE grantee IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
ORDER BY grantee,privilege;

PROMPT INVALID OBJECTS: this query must return no rows.
SELECT owner,object_name,object_type,status FROM DBA_OBJECTS
WHERE owner IN ('CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_BANK_A','CF_SIM_NBFC_B')
  AND status<>'VALID';

PROMPT Review the results above. Script completion alone does not mean every query passed.
