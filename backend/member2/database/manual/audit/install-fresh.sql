-- Run Script (F5) as CF_AUDIT in FREEPDB1. FRESH EMPTY SCHEMA ONLY.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
DECLARE n NUMBER; BEGIN
 IF USER <> 'CF_AUDIT' THEN RAISE_APPLICATION_ERROR(-20001,'Connect as CF_AUDIT'); END IF;
 SELECT COUNT(*) INTO n FROM user_tables;
 IF n > 0 THEN RAISE_APPLICATION_ERROR(-20002,'Schema is not empty. Use verify.sql; do not rerun the fresh installer.'); END IF;
END;
/
@@V1__event_delivery_tables.sql
@@V2__audit_events.sql
COMMIT;
