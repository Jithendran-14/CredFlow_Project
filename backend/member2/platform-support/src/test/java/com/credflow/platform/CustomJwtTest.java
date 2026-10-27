package com.credflow.platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import static org.assertj.core.api.Assertions.*;
class CustomJwtTest {
 @TempDir Path directory;
 KeyPair key;JwtDecoder decoder;
 @BeforeEach void setup()throws Exception {
  var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);key=gen.generateKeyPair();
  Path pem=directory.resolve("auth-public.pem");
  Files.writeString(pem,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
  decoder=new PlatformConfiguration().jwtDecoder("credflow-auth",pem.toString(),"credflow-api");
 }
 String token(KeyPair pair,String issuer,String audience,Instant expiry,String subject)throws Exception {
  var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
    .issueTime(Date.from(Instant.now())).claim("roles",List.of("ADMIN","ELIGIBILITY_SERVICE"));
  if(expiry!=null)claims.expirationTime(Date.from(expiry));
  var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());jwt.sign(new RSASSASigner((RSAPrivateKey)pair.getPrivate()));return jwt.serialize();
 }
 @Test void customAuthSignatureAndFlatRolesWork()throws Exception {
  var jwt=decoder.decode(token(key,"credflow-auth","credflow-api",Instant.now().plusSeconds(900),"user-123"));
  assertThat(PlatformConfiguration.roles(jwt)).extracting(Object::toString).containsExactly("ROLE_ADMIN","ROLE_ELIGIBILITY_SERVICE");
 }
 @Test void unrelatedSigningKeyIsRejected()throws Exception {
  var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
  String token=token(gen.generateKeyPair(),"credflow-auth","credflow-api",Instant.now().plusSeconds(900),"u");
  assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);
 }
 @Test void wrongIssuerIsRejected()throws Exception {String t=token(key,"other","credflow-api",Instant.now().plusSeconds(900),"u");assertThatThrownBy(()->decoder.decode(t)).isInstanceOf(JwtException.class);}
 @Test void wrongAudienceIsRejected()throws Exception {String t=token(key,"credflow-auth","other",Instant.now().plusSeconds(900),"u");assertThatThrownBy(()->decoder.decode(t)).isInstanceOf(JwtException.class);}
 @Test void expiredTokenIsRejected()throws Exception {String t=token(key,"credflow-auth","credflow-api",Instant.now().minusSeconds(300),"u");assertThatThrownBy(()->decoder.decode(t)).isInstanceOf(JwtException.class);}
 @Test void expiryIsRequired()throws Exception {String t=token(key,"credflow-auth","credflow-api",null,"u");assertThatThrownBy(()->decoder.decode(t)).isInstanceOf(JwtException.class);}
 @Test void subjectIsRequired()throws Exception {String t=token(key,"credflow-auth","credflow-api",Instant.now().plusSeconds(900),null);assertThatThrownBy(()->decoder.decode(t)).isInstanceOf(JwtException.class);}
 @Test void missingPublicKeyFailsClosed(){assertThatThrownBy(()->JwtKeys.read("")).isInstanceOf(IllegalStateException.class);}
 @Test void oldRealmRoleClaimDoesNotGrantAccess(){var jwt=Jwt.withTokenValue("test").header("alg","RS256").claim("realm_access",Map.of("roles",List.of("ADMIN"))).build();assertThat(PlatformConfiguration.roles(jwt)).isEmpty();}
}
