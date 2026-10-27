package com.credflow.gateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
public final class JwtKeys {
 private JwtKeys() {}
 public static RSAPublicKey read(String location) {
  if(location==null || location.isBlank()) throw new IllegalStateException("Set JWT_PUBLIC_KEY_PATH to the public PEM supplied by Auth Service");
  try {
   String pem;
   if(location.startsWith("classpath:")) {
    try(var in=JwtKeys.class.getResourceAsStream("/"+location.substring(10).replaceFirst("^/",""))) {
     if(in==null)throw new IllegalArgumentException("Public key resource not found");
     pem=new String(in.readAllBytes(),StandardCharsets.US_ASCII);
    }
   } else {
    Path path=location.startsWith("file:")?Path.of(java.net.URI.create(location)):Path.of(location);
    pem=Files.readString(path,StandardCharsets.US_ASCII);
   }
   if(!pem.contains("-----BEGIN PUBLIC KEY-----") || pem.contains("PRIVATE KEY"))throw new IllegalArgumentException("Expected X.509 PUBLIC KEY PEM");
   byte[] bytes=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""));
   var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
   if(key.getModulus().bitLength()<2048)throw new IllegalArgumentException("RSA public key must be at least 2048 bits");
   return key;
  }catch(Exception ex){throw new IllegalStateException("Cannot load Auth public PEM; check JWT_PUBLIC_KEY_PATH",ex);}
 }
}
