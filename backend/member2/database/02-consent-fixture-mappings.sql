-- Member 1 integration helper, not a Member 2 table installer.
-- Run as CF_CONSENT AFTER Member 1 creates these tables. F5 / Run Script.
-- Enter the REAL sellerId and marketplace UUID from your Application/Auth data.
-- Demo values must contain only letters, digits and hyphens. Existing mappings are preserved.
SET VERIFY OFF
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
ACCEPT seller_id CHAR PROMPT 'Seller ID (e.g. SELLER-1001): '
ACCEPT marketplace_id CHAR PROMPT 'Marketplace UUID from your application: '
DECLARE BEGIN
 IF USER <> 'CF_CONSENT' THEN RAISE_APPLICATION_ERROR(-20001,'Connect as CF_CONSENT'); END IF;
 IF NOT REGEXP_LIKE('&seller_id','^[A-Za-z0-9-]+$') OR NOT REGEXP_LIKE('&marketplace_id','^[A-Za-z0-9-]+$') THEN
  RAISE_APPLICATION_ERROR(-20002,'Use only letters, digits and hyphens in these demo IDs');
 END IF;
END;
/
MERGE INTO CF_SELLER_IDENTITY_MAP t USING (SELECT '&seller_id' s,'&marketplace_id' m FROM dual) v
ON (t.SELLER_ID=v.s AND t.MARKETPLACE_ID=v.m)
WHEN NOT MATCHED THEN INSERT(SELLER_ID,MARKETPLACE_ID,FIXTURE_IDENTITY_ID)
VALUES(v.s,v.m,'DEMO-VERIFIED-001');
MERGE INTO CF_FINANCIAL_PROVIDER_MAP t USING (SELECT '&seller_id' s,'&marketplace_id' m FROM dual) v
ON (t.SELLER_ID=v.s AND t.MARKETPLACE_ID=v.m AND t.SOURCE_PROVIDER='member2-financial-mock')
WHEN NOT MATCHED THEN INSERT(SELLER_ID,MARKETPLACE_ID,SOURCE_PROVIDER,SELLER_FIXTURE_ID)
VALUES(v.s,v.m,'member2-financial-mock','DEMO-FINANCIAL-GOOD-001');
COMMIT;
UNDEFINE seller_id
UNDEFINE marketplace_id
