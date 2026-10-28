-- Fresh-install catalog only. Does not create sellers, loans or offers.
-- The first marketplace officer is provisioned by SeedFull.java.
MERGE INTO CF_AUTH.CF_ORGANIZATIONS T USING (
 SELECT '11111111-1111-4111-8111-111111111111' ID,'Marketplace 1' NAME,'MARKETPLACE' KIND FROM DUAL
 UNION ALL SELECT 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','Sahyadri Commercial Bank','LENDER' FROM DUAL
 UNION ALL SELECT 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','Pragati Finance','LENDER' FROM DUAL
 UNION ALL SELECT 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3','Bank C','LENDER' FROM DUAL
) S ON (T.ORGANIZATION_ID=S.ID)
WHEN NOT MATCHED THEN INSERT (ORGANIZATION_ID,ORGANIZATION_NAME,ORGANIZATION_TYPE)
VALUES (S.ID,S.NAME,S.KIND);
COMMIT;
