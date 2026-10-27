package com.credflow.eligibility.integration;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded HTTP calls, client-credentials token caching and one refresh after a 401. */
@Component
public class Member1Client {
 private final ObjectMapper json;private final HttpClient http;
 private final String auth,application,consent,workflow,client,secret;
 private volatile String token;private volatile Instant expires=Instant.EPOCH;
 public Member1Client(ObjectMapper json,
  @Value("${credflow.clients.auth-url:http://localhost:8085}")String auth,
  @Value("${credflow.clients.application-url:http://localhost:8081}")String application,
  @Value("${credflow.clients.consent-url:http://localhost:8082}")String consent,
  @Value("${credflow.clients.workflow-url:http://localhost:8084}")String workflow,
  @Value("${credflow.clients.client-id:eligibility-service}")String client,
  @Value("${credflow.clients.client-secret:}")String secret) {
  this.json=json;this.auth=base(auth);this.application=base(application);this.consent=base(consent);this.workflow=base(workflow);this.client=client;this.secret=secret;
  this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 private static String base(String value) {
  URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Invalid service base URL");
  return value.replaceAll("/+$","");
 }
 public String application(UUID id,String correlation){return service("GET",application+"/internal/v1/applications/"+id+"/context",null,null,correlation);}
 public String ownership(UUID id,String seller,String correlation){return service("POST",application+"/internal/v1/applications/"+id+"/ownership-check",Map.of("sellerId",seller),null,correlation);}
 public String financial(UUID app,Map<String,Object> body,UUID key,String correlation){return service("POST",consent+"/internal/v1/applications/"+app+"/financial-data/retrievals",body,key.toString(),correlation);}
 public String financialAvailability(UUID app,String sellerToken,String correlation) {
  return request("GET",consent+"/api/v1/applications/"+app+"/financial-data-availability",null,null,correlation,sellerToken);
 }
 public String workflow(UUID app,String sellerToken,String correlation) {
  return request("GET",workflow+"/api/v1/applications/"+app+"/workflow",null,null,correlation,sellerToken);
 }
 private String service(String method,String url,Object body,String key,String correlation) {
  String current=accessToken();
  try{return request(method,url,body,key,correlation,current);}
  catch(UpstreamRejected rejected){if(rejected.status!=401)throw rejected;invalidate(current);return request(method,url,body,key,correlation,accessToken());}
 }
 private String request(String method,String url,Object body,String key,String correlation,String bearer) {
  try {
   var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+bearer)
    .header("Accept","application/json").header("X-Correlation-ID",correlation);
   if(key!=null)b.header("Idempotency-Key",key);
   if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());
   else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
   var response=http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
   if(response.statusCode()<200 || response.statusCode()>299)throw status(response.statusCode());
   if(response.body().length()>1_000_000)throw new UpstreamRejected(502);
   return response.body();
  }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new UpstreamUnavailable();}
   catch(java.io.IOException ex){throw new UpstreamUnavailable();}
 }
 private synchronized String accessToken() {
  if(token!=null && Instant.now().isBefore(expires))return token;
  if(secret.isBlank())throw new UpstreamUnavailable();
  try {
   String basic=Base64.getEncoder().encodeToString((client+":"+secret).getBytes(StandardCharsets.UTF_8));
   var request=HttpRequest.newBuilder(URI.create(auth+"/oauth2/token")).timeout(Duration.ofSeconds(10))
    .header("Authorization","Basic "+basic).header("Content-Type","application/x-www-form-urlencoded")
    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=financial-data.read")).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200)throw new UpstreamUnavailable();
   JsonNode value=json.readTree(response.body());long seconds=value.path("expires_in").asLong(0);
   if(!"Bearer".equalsIgnoreCase(value.path("token_type").asText()) || !value.path("access_token").isTextual() || value.path("access_token").asText().isBlank() || seconds<1)throw new UpstreamUnavailable();
   token=value.path("access_token").asText();expires=Instant.now().plusSeconds(Math.max(1,seconds-30));return token;
  }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new UpstreamUnavailable();}
   catch(java.io.IOException ex){throw new UpstreamUnavailable();}
 }
 private synchronized void invalidate(String old){if(Objects.equals(token,old)){token=null;expires=Instant.EPOCH;}}
 private RuntimeException status(int status){return status>=500 || status==429?new UpstreamUnavailable():new UpstreamRejected(status);}
 public static class UpstreamUnavailable extends RuntimeException {public UpstreamUnavailable(){super("Trusted service is unavailable");}}
 public static class UpstreamRejected extends IllegalArgumentException {public final int status;public UpstreamRejected(int status){super("Trusted service rejected the request");this.status=status;}}
}
