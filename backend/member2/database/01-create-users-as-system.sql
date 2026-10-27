-- Run as SYSTEM in FREEPDB1 using Run Script (F5). Run once for a NEW setup.
-- Stops before CREATE USER if any intended username already exists. Never resets passwords.
-- Passwords entered below are quoted Oracle passwords. Do not include a double quote or &.
SET ECHO OFF
SET VERIFY OFF
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
DECLARE
 n NUMBER;
BEGIN
 IF SYS_CONTEXT('USERENV','CON_NAME') <> 'FREEPDB1' THEN
  RAISE_APPLICATION_ERROR(-20001,'Connect SYSTEM to FREEPDB1, not CDB$ROOT');
 END IF;
 SELECT COUNT(*) INTO n FROM dba_users WHERE username IN ('CF_ELIGIBILITY','CF_AUDIT','CF_SIM_FINANCIAL');
 IF n > 0 THEN RAISE_APPLICATION_ERROR(-20002,'At least one schema already exists. Do not recreate it; inspect existing users and create only missing users manually.'); END IF;
END;
/
ACCEPT eligibility_password CHAR PROMPT 'New CF_ELIGIBILITY password: ' HIDE
ACCEPT audit_password CHAR PROMPT 'New CF_AUDIT password: ' HIDE
ACCEPT mock_password CHAR PROMPT 'New CF_SIM_FINANCIAL password: ' HIDE
CREATE USER CF_ELIGIBILITY IDENTIFIED BY "&eligibility_password" DEFAULT TABLESPACE USERS QUOTA 100M ON USERS;
CREATE USER CF_AUDIT IDENTIFIED BY "&audit_password" DEFAULT TABLESPACE USERS QUOTA 100M ON USERS;
CREATE USER CF_SIM_FINANCIAL IDENTIFIED BY "&mock_password" DEFAULT TABLESPACE USERS QUOTA 100M ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE TO CF_ELIGIBILITY;
GRANT CREATE SESSION, CREATE TABLE TO CF_AUDIT;
GRANT CREATE SESSION, CREATE TABLE TO CF_SIM_FINANCIAL;
UNDEFINE eligibility_password
UNDEFINE audit_password
UNDEFINE mock_password
PROMPT Users created. Save three separate SQL Developer connections; use the matching username and password.
