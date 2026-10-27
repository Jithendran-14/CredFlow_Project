import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Idempotent Bank C identity provisioning. Uses private configuration and never prints credentials. */
public class ProvisionPhase0 {
 public static void main(String[] args)throws Exception{
  Path dir=Path.of(args[0]);Properties p=new Properties();try(var reader=Files.newBufferedReader(dir.resolve("runtime.properties"))){p.load(reader);}
  try(var c=DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.auth.user"),p.getProperty("db.auth.password"))){
   c.setAutoCommit(false);
   try(var s=c.createStatement();var r=s.executeQuery("SELECT USER,SYS_CONTEXT('USERENV','CON_NAME') FROM DUAL")){r.next();if(!"CF_AUTH".equals(r.getString(1))||!"FREEPDB1".equals(r.getString(2)))throw new IllegalStateException("Wrong Oracle target");}
   String lender="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3";
   long officer=user(c,p.getProperty("account.bank-c.username"),"bank.c.officer@credflow.test",p.getProperty("account.bank-c.password"),"LENDER_OFFICER",lender);
   try(var fix=c.prepareStatement("UPDATE CF_AUTH_USERS SET LENDER_ID=NULL,UPDATED_AT=SYSTIMESTAMP WHERE USERNAME='service.mock-bank-c' AND USER_ROLE='LENDER_SERVICE' AND LENDER_ID=? AND USER_ID IN (SELECT USER_ID FROM CF_AUTH_SERVICE_CLIENTS WHERE CLIENT_ID='mock-bank-c' AND SCOPES='lender.callback')")){fix.setString(1,lender);fix.executeUpdate();}
   long machine=user(c,"service.mock-bank-c","mock-bank-c@service.credflow.invalid",p.getProperty("client.mock-bank-c.secret"),"LENDER_SERVICE",null);
   try(var s=c.prepareStatement("SELECT SCOPES FROM CF_AUTH_SERVICE_CLIENTS WHERE CLIENT_ID='mock-bank-a'");var r=s.executeQuery()){
    if(!r.next())throw new IllegalStateException("Bank A client contract missing");String scopes=r.getString(1);
    try(var q=c.prepareStatement("MERGE INTO CF_AUTH_SERVICE_CLIENTS T USING (SELECT ? USER_ID,? SCOPES FROM DUAL) S ON(T.CLIENT_ID='mock-bank-c') WHEN NOT MATCHED THEN INSERT(USER_ID,CLIENT_ID,SCOPES) VALUES(S.USER_ID,'mock-bank-c',S.SCOPES)")){q.setLong(1,machine);q.setString(2,scopes);q.executeUpdate();}
   }
   c.commit();
  }
  System.out.println("Bank C officer and scoped simulator identity are ready; credentials are in private runtime settings.");
 }
 static long user(Connection c,String username,String email,String password,String role,String lender)throws Exception{
  if(username==null||password==null)throw new IllegalStateException("Private Bank C settings are missing");
  try(var s=c.prepareStatement("SELECT USER_ID,PASSWORD_HASH,USER_ROLE,LENDER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){s.setString(1,username);try(var r=s.executeQuery()){if(r.next()){if(!role.equals(r.getString(3))||!Objects.equals(lender,r.getString(4))||!BCrypt.checkpw(password,r.getString(2)))throw new IllegalStateException("Existing Bank C identity differs; no password was reset");return r.getLong(1);}}}
  try(var s=c.prepareStatement("INSERT INTO CF_AUTH_USERS(PUBLIC_USER_ID,USERNAME,EMAIL_ADDRESS,PASSWORD_HASH,USER_ROLE,LENDER_ID,ACCOUNT_STATUS,CREATED_AT,UPDATED_AT) VALUES(?,?,?,?,?,?,'ACTIVE',SYSTIMESTAMP,SYSTIMESTAMP)")){
   s.setString(1,UUID.randomUUID().toString());s.setString(2,username);s.setString(3,email);s.setString(4,BCrypt.hashpw(password,BCrypt.gensalt(12)));s.setString(5,role);s.setString(6,lender);s.executeUpdate();
  }
  try(var s=c.prepareStatement("SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){s.setString(1,username);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
 }
}
