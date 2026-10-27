import java.nio.file.*;import java.sql.*;import java.util.*;import java.security.*;import java.nio.charset.StandardCharsets;
/** Opt-in verification helper. Mutations are restricted to freshly registered recovery fixtures. */
public class RecoveryOracle {
 public static void main(String[] args)throws Exception {
  var props=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]).resolve("runtime.properties"))){props.load(r);}
  var input=new Properties();input.load(System.in);String username=input.getProperty("username"),token=input.getProperty("token","");
  if(username==null||!username.startsWith("verify.recovery."))throw new IllegalArgumentException("Only recovery verification fixtures may be accessed.");
  String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
  try(var c=DriverManager.getConnection(props.getProperty("db.url"),props.getProperty("db.auth.user"),props.getProperty("db.auth.password"))){
   if(args[1].equals("expire")){try(var s=c.prepareStatement("UPDATE CF_PASSWORD_RESET_TOKENS SET EXPIRES_AT=SYSTIMESTAMP-INTERVAL '1' SECOND WHERE TOKEN_HASH=? AND USER_ID=(SELECT USER_ID FROM CF_AUTH_USERS WHERE USERNAME=?)")){s.setString(1,digest);s.setString(2,username);if(s.executeUpdate()!=1)throw new IllegalStateException("Verification token not found");}}
   else if(args[1].equals("inactive")||args[1].equals("active")){try(var s=c.prepareStatement("UPDATE CF_AUTH_USERS SET ACCOUNT_STATUS=? WHERE USERNAME=?")){s.setString(1,args[1].equals("active")?"ACTIVE":"DISABLED");s.setString(2,username);if(s.executeUpdate()!=1)throw new IllegalStateException("Verification account not found");}}
   else {try(var s=c.prepareStatement("SELECT T.TOKEN_HASH,T.USED_AT,U.PASSWORD_HASH FROM CF_PASSWORD_RESET_TOKENS T JOIN CF_AUTH_USERS U ON U.USER_ID=T.USER_ID WHERE U.USERNAME=? AND T.TOKEN_HASH=?")){s.setString(1,username);s.setString(2,digest);try(var r=s.executeQuery()){if(!r.next()||!digest.equals(r.getString(1))||token.equals(r.getString(1))||!r.getString(3).startsWith("$2"))throw new IllegalStateException("Oracle hash assertion failed");if(args[1].equals("used")&&r.getObject(2)==null)throw new IllegalStateException("Token was not consumed");if(args[1].equals("unused")&&r.getObject(2)!=null)throw new IllegalStateException("Token was unexpectedly consumed");}}}
  }System.out.println("PASS: Oracle recovery "+args[1]);
 }
}
