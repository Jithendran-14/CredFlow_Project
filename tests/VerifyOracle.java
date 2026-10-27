import java.nio.file.*;import java.sql.*;import java.util.*;
/** Read-only Oracle assertions for the opt-in API regression scripts. */
public class VerifyOracle {
 public static void main(String[] args)throws Exception{var props=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]).resolve("runtime.properties"))){props.load(r);}String label=args[1].equals("rules")?"eligibility":"application";try(var c=DriverManager.getConnection(props.getProperty("db.url"),props.getProperty("db."+label+".user"),props.getProperty("db."+label+".password"))){
  if(label.equals("eligibility")){try(var s=c.prepareStatement("SELECT COUNT(*) FROM lender_product_rule R WHERE product_id=? AND active=1 AND rule_version=(SELECT MAX(V.rule_version) FROM lender_product_rule V WHERE V.product_id=R.product_id)")){s.setString(1,args[2]);try(var r=s.executeQuery()){r.next();if(r.getInt(1)!=(Boolean.parseBoolean(args[3])?1:0))throw new IllegalStateException("Current rule activation does not match Oracle evaluator query");}}}
  else {try(var s=c.prepareStatement("SELECT JSON_VALUE(BEFORE_JSON,'$.requestedAmount'),JSON_VALUE(AFTER_JSON,'$.requestedAmount'),EXPECTED_REVISION,NEW_REVISION FROM CF_APPLICATION_CORRECTIONS WHERE APPLICATION_ID=? AND STATUS='APPLIED'")){s.setString(1,args[2]);try(var r=s.executeQuery()){if(!r.next()||!"200000.00".equals(r.getString(1))||!"180000.00".equals(r.getString(2))||r.getInt(3)!=1||r.getInt(4)!=2)throw new IllegalStateException("Oracle correction snapshots/revisions differ");}}}
 }System.out.println("PASS: Oracle "+args[1]+" persistence assertion");}
}
