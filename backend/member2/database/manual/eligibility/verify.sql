-- Read-only: run as CF_ELIGIBILITY. Does not change existing data.
SELECT USER FROM dual;
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT object_name, object_type, status FROM user_objects WHERE status <> 'VALID';
SELECT name, line, text FROM user_errors ORDER BY name, sequence;
