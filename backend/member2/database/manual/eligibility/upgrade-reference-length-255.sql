-- Existing installation only. Run as CF_ELIGIBILITY. No rows are deleted.
DECLARE
  current_length NUMBER;
  current_semantics VARCHAR2(1);
BEGIN
  IF USER <> 'CF_ELIGIBILITY' THEN
    RAISE_APPLICATION_ERROR(-20001, 'Connect as CF_ELIGIBILITY');
  END IF;
  SELECT char_length, char_used INTO current_length, current_semantics
    FROM user_tab_columns WHERE table_name='ELIGIBILITY_OPERATION' AND column_name='RETRIEVAL_REFERENCE' AND data_type='VARCHAR2';
  IF current_length < 255 OR (current_length=255 AND current_semantics='B') THEN
    EXECUTE IMMEDIATE 'ALTER TABLE eligibility_operation MODIFY (retrieval_reference VARCHAR2(255 CHAR))';
  END IF;
END;
/
