WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CURRENT_SCHEMA=CF_ELIGIBILITY;
INSERT INTO lender_product_rule SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',1,
 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','Bank A working capital',12,300000,15,100000,1000000,1,1,1 FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_rule WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1' AND rule_version=1);
INSERT INTO lender_product_rule SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',1,
 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','NBFC B working capital',6,150000,25,50000,800000,1,2,1 FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_rule WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2' AND rule_version=1);
INSERT INTO lender_product_purpose SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',1,'PURCHASE_INVENTORY' FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_purpose WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1' AND rule_version=1 AND purpose='PURCHASE_INVENTORY');
INSERT INTO lender_product_purpose SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',1,'WORKING_CAPITAL' FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_purpose WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1' AND rule_version=1 AND purpose='WORKING_CAPITAL');
INSERT INTO lender_product_purpose SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',1,'PURCHASE_INVENTORY' FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_purpose WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2' AND rule_version=1 AND purpose='PURCHASE_INVENTORY');
INSERT INTO lender_product_purpose SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',1,'WORKING_CAPITAL' FROM dual WHERE NOT EXISTS (SELECT 1 FROM lender_product_purpose WHERE product_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2' AND rule_version=1 AND purpose='WORKING_CAPITAL');
COMMIT;
