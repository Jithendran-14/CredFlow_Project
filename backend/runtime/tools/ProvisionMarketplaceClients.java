import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Provision one restricted backend submission client per existing marketplace. No human passwords are changed. */
public class ProvisionMarketplaceClients {
 public static void main(String[] args)throws Exception{
  Path privateDir=Path.of(args[0]);Properties db=new Properties();try(var r=Files.newBufferedReader(privateDir.resolve("runtime.properties"),StandardCharsets.UTF_8)){db.load(r);}
  Path file=privateDir.resolve("marketplace-clients.properties");Properties clients=new Properties();if(Files.exists(file))try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){clients.load(r);}
  Class.forName("oracle.jdbc.OracleDriver");int count=0;
  try(var c=DriverManager.getConnection(db.getProperty("db.url"),db.getProperty("db.auth.user"),db.getProperty("db.auth.password"))){
   c.setAutoCommit(false);List<String> markets=new ArrayList<>();
   try(var st=c.createStatement();var r=st.executeQuery("SELECT DISTINCT MARKETPLACE_ID FROM CF_AUTH_USERS U WHERE USER_ROLE='MARKETPLACE' AND ACCOUNT_STATUS='ACTIVE' AND MARKETPLACE_ID IS NOT NULL AND NOT EXISTS(SELECT 1 FROM CF_AUTH_SERVICE_CLIENTS C WHERE C.USER_ID=U.USER_ID)")){while(r.next())markets.add(UUID.fromString(r.getString(1)).toString());}
   for(String market:markets){
    String client="marketplace-submission-"+market,name="service.marketplace."+market,secret=clients.getProperty("marketplace."+market+".secret");Long userId=null;
    try(var q=c.prepareStatement("SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){q.setString(1,name);try(var r=q.executeQuery()){if(r.next())userId=r.getLong(1);}}
    if(userId!=null&&secret==null)throw new IllegalStateException("Existing submission client has no local secret; restore its private credential file instead of resetting it.");
    if(secret==null){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    if(userId==null){
     // Persist the secret before committing its hash: a failed DB transaction can safely be retried.
     clients.setProperty("marketplace."+market+".client-id",client);clients.setProperty("marketplace."+market+".secret",secret);
     try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){clients.store(writer,"Private backend-only marketplace submission clients. Never publish.");}
     try(var q=c.prepareStatement("INSERT INTO CF_AUTH_USERS(PUBLIC_USER_ID,USERNAME,EMAIL_ADDRESS,PASSWORD_HASH,USER_ROLE,MARKETPLACE_ID,ACCOUNT_STATUS) VALUES(?,?,?,?,'MARKETPLACE',?,'ACTIVE')")){q.setString(1,UUID.randomUUID().toString());q.setString(2,name);q.setString(3,name+"@service.internal");q.setString(4,BCrypt.hashpw(secret,BCrypt.gensalt(12)));q.setString(5,market);q.executeUpdate();}
     try(var q=c.prepareStatement("SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?")){q.setString(1,name);try(var r=q.executeQuery()){r.next();userId=r.getLong(1);}}
    }
    try(var q=c.prepareStatement("MERGE INTO CF_AUTH_SERVICE_CLIENTS T USING(SELECT ? USER_ID,? CLIENT_ID FROM DUAL) S ON(T.CLIENT_ID=S.CLIENT_ID) WHEN NOT MATCHED THEN INSERT(USER_ID,CLIENT_ID,SCOPES) VALUES(S.USER_ID,S.CLIENT_ID,'application.submit')")){q.setLong(1,userId);q.setString(2,client);q.executeUpdate();}
    clients.setProperty("marketplace."+market+".client-id",client);count++;
   }
   c.commit();try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){clients.store(writer,"Private backend-only marketplace submission clients. Never publish.");}
  }
  Path config=privateDir.resolve("auth.properties");List<String> lines=new ArrayList<>(Files.readAllLines(config,StandardCharsets.UTF_8));lines.removeIf(l->l.startsWith("credflow.marketplace-submission."));
  lines.add("credflow.marketplace-submission.enabled=true");lines.add("credflow.marketplace-submission.credentials-file="+file.toAbsolutePath().toString().replace('\\','/'));
  lines.add("credflow.marketplace-submission.gateway-url=http://127.0.0.1:5174/api/proxy/application/api/v1/applications");Files.write(config,lines,StandardCharsets.UTF_8);
  System.out.println("Backend marketplace clients configured: "+count+". Human credentials preserved; secrets remain private.");
 }
}
