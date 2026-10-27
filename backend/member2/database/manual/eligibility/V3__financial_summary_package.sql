CREATE OR REPLACE PACKAGE CF_FINANCIAL_SUMMARY_PKG AS
 -- The explicit batch overload prevents mixing refreshes of the same revision.
 PROCEDURE BUILD_SNAPSHOT(p_application_id IN VARCHAR2, p_application_revision IN NUMBER,
   p_as_of_date IN DATE, p_import_batch_id IN VARCHAR2, o_snapshot_id OUT VARCHAR2);
 PROCEDURE BUILD_SNAPSHOT(p_application_id IN VARCHAR2, p_application_revision IN NUMBER,
   p_as_of_date IN DATE, o_snapshot_id OUT VARCHAR2);
END CF_FINANCIAL_SUMMARY_PKG;
/
CREATE OR REPLACE PACKAGE BODY CF_FINANCIAL_SUMMARY_PKG AS
 PROCEDURE BUILD_SNAPSHOT(p_application_id IN VARCHAR2, p_application_revision IN NUMBER,
   p_as_of_date IN DATE, p_import_batch_id IN VARCHAR2, o_snapshot_id OUT VARCHAR2) IS
  v_count NUMBER; v_complete NUMBER; v_bad NUMBER;
  v_gross NUMBER; v_returns NUMBER; v_payout NUMBER; v_orders NUMBER; v_returned NUMBER;
  v_reason VARCHAR2(80); v_outcome VARCHAR2(20); v_coverage VARCHAR2(10);
 BEGIN
  -- Lock the import row so two retries cannot both create a snapshot.
  SELECT import_batch_id INTO o_snapshot_id FROM financial_import_batch
   WHERE import_batch_id=p_import_batch_id AND application_id=p_application_id
    AND application_revision=p_application_revision AND as_of_date=TRUNC(p_as_of_date)
   FOR UPDATE;
  SELECT COUNT(*) INTO v_count FROM financial_snapshot WHERE import_batch_id=p_import_batch_id;
  IF v_count=1 THEN
   SELECT snapshot_id INTO o_snapshot_id FROM financial_snapshot WHERE import_batch_id=p_import_batch_id;
   RETURN;
  END IF;
  SELECT COUNT(*), NVL(SUM(CASE WHEN coverage_status='COMPLETE' THEN 1 ELSE 0 END),0),
    NVL(SUM(CASE WHEN returns_amount>gross_sales OR returned_order_count>order_count THEN 1 ELSE 0 END),0),
    NVL(SUM(gross_sales),0),NVL(SUM(returns_amount),0),NVL(SUM(payout_amount),0),
    NVL(SUM(order_count),0),NVL(SUM(returned_order_count),0)
   INTO v_count,v_complete,v_bad,v_gross,v_returns,v_payout,v_orders,v_returned
   FROM financial_source_month WHERE import_batch_id=p_import_batch_id
    AND month_start>=ADD_MONTHS(TRUNC(p_as_of_date,'MM'),-3)
    AND month_start<TRUNC(p_as_of_date,'MM');
  v_outcome:='EVALUATED'; v_coverage:='COMPLETE'; v_reason:=NULL;
  IF v_count<>3 OR v_complete<>3 THEN
   v_outcome:='ACTION_REQUIRED';v_coverage:='INCOMPLETE';v_reason:='FINANCIAL_DATA_INCOMPLETE';
  ELSIF v_bad>0 THEN
   v_outcome:='ACTION_REQUIRED';v_reason:='FINANCIAL_DATA_INCONSISTENT';
  ELSIF v_gross=0 THEN
   v_outcome:='ACTION_REQUIRED';v_reason:='FINANCIAL_DATA_INSUFFICIENT';
  END IF;
  o_snapshot_id:=LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()),
    '^(.{8})(.{4})(.{4})(.{4})(.{12})$','\1-\2-\3-\4-\5'));
  INSERT INTO financial_snapshot(snapshot_id,import_batch_id,application_id,application_revision,
    as_of_date,coverage_status,outcome,reason_code,total_gross,total_returns,total_payout,total_orders,total_returned_orders)
   VALUES(o_snapshot_id,p_import_batch_id,p_application_id,p_application_revision,TRUNC(p_as_of_date),
    v_coverage,v_outcome,v_reason,v_gross,v_returns,v_payout,v_orders,v_returned);
  FOR i IN 1..3 LOOP
   INSERT INTO financial_snapshot_month(snapshot_id,month_start,coverage_status,gross_sales,returns_amount,
    payout_amount,order_count,returned_order_count)
   SELECT o_snapshot_id,m.month_start,NVL(s.coverage_status,'INCOMPLETE'),s.gross_sales,s.returns_amount,
    s.payout_amount,s.order_count,s.returned_order_count
   FROM (SELECT ADD_MONTHS(TRUNC(p_as_of_date,'MM'),-i) month_start FROM dual) m
   LEFT JOIN financial_source_month s ON s.import_batch_id=p_import_batch_id AND s.month_start=m.month_start;
  END LOOP;
  -- No COMMIT: the Java caller owns the transaction and rollback.
 END BUILD_SNAPSHOT;
 PROCEDURE BUILD_SNAPSHOT(p_application_id IN VARCHAR2, p_application_revision IN NUMBER,
   p_as_of_date IN DATE, o_snapshot_id OUT VARCHAR2) IS
  v_batch VARCHAR2(36); v_count NUMBER;
 BEGIN
  SELECT COUNT(*),MIN(import_batch_id) INTO v_count,v_batch FROM financial_import_batch
   WHERE application_id=p_application_id AND application_revision=p_application_revision
    AND as_of_date=TRUNC(p_as_of_date);
  IF v_count<>1 THEN
   RAISE_APPLICATION_ERROR(-20001,'Exactly one import required; use explicit batch overload for refreshes');
  END IF;
  BUILD_SNAPSHOT(p_application_id,p_application_revision,p_as_of_date,v_batch,o_snapshot_id);
 END BUILD_SNAPSHOT;
END CF_FINANCIAL_SUMMARY_PKG;
/
