import java.nio.file.*;import java.sql.*;import java.util.*;import org.springframework.security.crypto.bcrypt.BCrypt;
public class SeedFull {
 static String bank="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1",nbfc="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2",market="11111111-1111-4111-8111-111111111111";
 public static void main(String[] args)throws Exception{
  var p=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){p.load(r);}Class.forName("oracle.jdbc.OracleDriver");
  try(var c=DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.auth.user"),p.getProperty("db.auth.password"))){
   c.setAutoCommit(false);
   for(String[] user:List.of(new String[]{"admin","ADMIN",null,null,null},new String[]{"seller","SELLER","SELLER-INTEGRATION-104",null,null},new String[]{"other-seller","SELLER","SELLER-INTEGRATION-OTHER",null,null},new String[]{"marketplace","MARKETPLACE",null,market,null},new String[]{"bank-a","LENDER_OFFICER",null,null,bank},new String[]{"nbfc-b","LENDER_OFFICER",null,null,nbfc})){
    addUser(c,"integration."+user[0],p.getProperty("account."+user[0]+".password"),user[1],user[2],user[3],user[4]);
   }
   for(String[] client:List.of(new String[]{"eligibility-service","ELIGIBILITY_SERVICE","financial-data.read"},new String[]{"consent-verification-service","VERIFICATION_SERVICE","workflow.consent"},new String[]{"orchestration-service","ORCHESTRATION_SERVICE","verification.request integration.write eligibility.evaluate"},new String[]{"integration-service","INTEGRATION_SERVICE","workflow.internal lender.lookup lender.submit lender.command"},new String[]{"mock-bank-a","LENDER_SERVICE","lender.callback"},new String[]{"mock-nbfc-b","LENDER_SERVICE","lender.callback"})){
    long id=addUser(c,"integration.client."+client[0],p.getProperty("client."+client[0]+".secret"),client[1],null,null,null);
    try(var q=c.prepareStatement("MERGE INTO CF_AUTH_SERVICE_CLIENTS t USING (SELECT ? USER_ID, ? CLIENT_ID, ? SCOPES FROM DUAL) s ON (t.CLIENT_ID=s.CLIENT_ID) WHEN MATCHED THEN UPDATE SET t.SCOPES=s.SCOPES WHEN NOT MATCHED THEN INSERT (USER_ID,CLIENT_ID,SCOPES) VALUES(s.USER_ID,s.CLIENT_ID,s.SCOPES)")){q.setLong(1,id);q.setString(2,client[0]);q.setString(3,client[2]);q.executeUpdate();}
   }
   c.commit();System.out.println("Auth: integration users and six scoped clients provisioned.");
  }
  try(var c=DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.application.user"),p.getProperty("db.application.password"));var q=c.prepareStatement("MERGE INTO CF_SELLER_MAPPINGS t USING(SELECT ? MARKETPLACE_ID, ? MARKETPLACE_SELLER_ID, ? SELLER_ID FROM DUAL) s ON(t.MARKETPLACE_ID=s.MARKETPLACE_ID AND t.MARKETPLACE_SELLER_ID=s.MARKETPLACE_SELLER_ID) WHEN NOT MATCHED THEN INSERT(MARKETPLACE_ID,MARKETPLACE_SELLER_ID,SELLER_ID) VALUES(s.MARKETPLACE_ID,s.MARKETPLACE_SELLER_ID,s.SELLER_ID)")){
   q.setString(1,market);q.setString(2,"INTEGRATION-104");q.setString(3,"SELLER-INTEGRATION-104");q.executeUpdate();System.out.println("Application: isolated trusted seller mapping provisioned.");
  }
  try(var c=DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.consent.user"),p.getProperty("db.consent.password"));var st=c.createStatement()){
   st.executeUpdate("MERGE INTO CF_SELLER_IDENTITY_MAP t USING (SELECT 'SELLER-INTEGRATION-104' s,'11111111-1111-4111-8111-111111111111' m FROM dual) v ON (t.SELLER_ID=v.s AND t.MARKETPLACE_ID=v.m) WHEN NOT MATCHED THEN INSERT(SELLER_ID,MARKETPLACE_ID,FIXTURE_IDENTITY_ID) VALUES(v.s,v.m,'DEMO-VERIFIED-001')");
   st.executeUpdate("MERGE INTO CF_FINANCIAL_PROVIDER_MAP t USING (SELECT 'SELLER-INTEGRATION-104' s,'11111111-1111-4111-8111-111111111111' m FROM dual) v ON (t.SELLER_ID=v.s AND t.MARKETPLACE_ID=v.m AND t.SOURCE_PROVIDER='member2-financial-mock') WHEN NOT MATCHED THEN INSERT(SELLER_ID,MARKETPLACE_ID,SOURCE_PROVIDER,SELLER_FIXTURE_ID) VALUES(v.s,v.m,'member2-financial-mock','DEMO-FINANCIAL-GOOD-001')");System.out.println("Identity and financial fixture mappings ready.");
  }

 }
 static long addUser(Connection c,String name,String password,String role,String seller,String marketplace,String lender)throws Exception{
  try(var q=c.prepareStatement("SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){q.setString(1,name);try(var r=q.executeQuery()){if(r.next())return r.getLong(1);}}
  String sql="INSERT INTO CF_AUTH_USERS(PUBLIC_USER_ID,USERNAME,EMAIL_ADDRESS,PASSWORD_HASH,USER_ROLE,SELLER_ID,MARKETPLACE_ID,LENDER_ID,ACCOUNT_STATUS) VALUES(?,?,?,?,?,?,?,?,'ACTIVE')";
  try(var q=c.prepareStatement(sql)){q.setString(1,UUID.randomUUID().toString());q.setString(2,name);q.setString(3,name+"@credflow.example");q.setString(4,BCrypt.hashpw(password,BCrypt.gensalt(12)));q.setString(5,role);q.setString(6,seller);q.setString(7,marketplace);q.setString(8,lender);q.executeUpdate();}
  try(var q=c.prepareStatement("SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){q.setString(1,name);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
 }
}
