-- One-time local recovery of four confirmed Kafka-offset payload conflicts.
-- Retains old inbox records and event receipts; no application statuses are edited.
-- The live consumer will process the current broker messages on its next retry.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK;
SET SERVEROUTPUT ON;
ALTER SESSION SET CONTAINER=FREEPDB1;
DECLARE n NUMBER;
BEGIN
 SELECT COUNT(*) INTO n FROM CF_APPLICATION.CF_WORKFLOW_STATUS_INBOX
 WHERE TOPIC_NAME='application.status.changed' AND PARTITION_NUMBER=0
 AND ((RECORD_OFFSET=640 AND RAW_HASH='6c4b84a8ff2a80b3203719840f5785ed96e0db6c6159f3c00903deedec767261')
 OR (RECORD_OFFSET=641 AND RAW_HASH='561bf0e29e9e9c0dbefc06a7550a0ba8812f92b4c364c13728a1078ab01eb94e')
 OR (RECORD_OFFSET=642 AND RAW_HASH='1c0b7baafda26b0611e1b5ed9c1174de860946be349fb7069aef9fa6459a4dea')
 OR (RECORD_OFFSET=643 AND RAW_HASH='f1ffa4f79e36cad2c903aec8a41e7824a4f094523b9ec3f2f6460e0423f64507'));
 IF n<>4 THEN RAISE_APPLICATION_ERROR(-20001,'Expected four confirmed conflicts; no changes made.'); END IF;
 UPDATE CF_APPLICATION.CF_WORKFLOW_STATUS_INBOX
 SET TOPIC_NAME='application.status.changed.archive-20261025'
 WHERE TOPIC_NAME='application.status.changed' AND PARTITION_NUMBER=0 AND RECORD_OFFSET BETWEEN 640 AND 643;
 DBMS_OUTPUT.PUT_LINE('Archived transport identities for '||SQL%ROWCOUNT||' preserved inbox records.');
END;
/
COMMIT;
EXIT SUCCESS;
