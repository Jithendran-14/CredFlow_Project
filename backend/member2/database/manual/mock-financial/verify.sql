SELECT USER, SYS_CONTEXT('USERENV','CON_NAME') AS container_name FROM dual;
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT object_name, status FROM user_objects WHERE status <> 'VALID';
SELECT COUNT(*) AS saved_mock_records FROM CF_MOCK_FINANCIAL_RECORD;
