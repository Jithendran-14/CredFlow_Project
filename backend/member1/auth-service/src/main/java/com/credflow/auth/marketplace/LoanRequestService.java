package com.credflow.auth.marketplace;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LoanRequestService {
 private final JdbcTemplate jdbc;
 private final ObjectMapper mapper;
 private final ActiveApplicationClient applications;
 public LoanRequestService(JdbcTemplate jdbc,ObjectMapper mapper,ActiveApplicationClient applications){this.jdbc=jdbc;this.mapper=mapper;this.applications=applications;}
 private String identity(Jwt jwt,String role,String claim){
  if(jwt.getClaimAsStringList("roles")==null||!jwt.getClaimAsStringList("roles").contains(role)||jwt.getClaimAsString(claim)==null)
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Required account identity is missing.");
  return jwt.getClaimAsString(claim);
 }
 @Transactional
 public Map<String,Object> submit(LoanRequestInput input,String key,Jwt jwt){
  String sellerId=identity(jwt,"SELLER","sellerId");
  try{UUID.fromString(key);}catch(Exception e){throw new IllegalArgumentException("Idempotency-Key must be a UUID.");}
  BigDecimal amount=new BigDecimal(input.requestedAmount());
  if(amount.signum()<=0||amount.compareTo(new BigDecimal("999999999.99"))>0||!Boolean.TRUE.equals(input.initialSharingAuthorised()))throw new IllegalArgumentException("A positive amount and initial sharing permission are required.");
  if(input.phase0()==null)throw new IllegalArgumentException("Preferred tenure, declared finances and prototype KYC are required.");input.phase0().validate();
  String canonical=mapper.writeValueAsString(input.phase0())+"|"+amount.setScale(2).toPlainString()+"|"+input.loanPurpose()+"|v1";
  String fingerprint;
  try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  // Lock the stable seller row before replay/queue checks; two different keys cannot queue two requests.
  var locked=jdbc.queryForList("SELECT SELLER_ID FROM CF_SELLER_PROFILES WHERE SELLER_ID=? FOR UPDATE",sellerId);
  if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Complete your marketplace seller registration before applying for a loan.");
  var previous=jdbc.queryForList("SELECT * FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE SELLER_ID=? AND IDEMPOTENCY_KEY=?",sellerId,key);
  if(!previous.isEmpty())return replay(previous.getFirst(),fingerprint);
  applications.requireNoActive(jwt,input.loanPurpose());
  if(jdbc.queryForObject("SELECT COUNT(*) FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE SELLER_ID=? AND LOAN_PURPOSE=? AND APPLICATION_ID IS NULL AND STATUS IN ('QUEUED','PROCESSING','RETRY_PENDING','ACTION_REQUIRED')",Integer.class,sellerId,input.loanPurpose())>0)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"You already have a loan request awaiting submission. Your marketplace must resolve it before you apply again.");
  var profiles=jdbc.queryForList("SELECT P.*,U.PUBLIC_USER_ID FROM CF_SELLER_PROFILES P JOIN CF_AUTH_USERS U ON U.USER_ID=P.USER_ID WHERE P.SELLER_ID=? AND U.PUBLIC_USER_ID=? AND U.ACCOUNT_STATUS='ACTIVE'",sellerId,jwt.getSubject());
  if(profiles.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Complete your marketplace seller registration before applying for a loan.");
  var profile=profiles.getFirst();String market=profile.get("MARKETPLACE_ID").toString();
  var organization=jdbc.queryForList("SELECT ORGANIZATION_STATUS FROM CF_ORGANIZATIONS WHERE ORGANIZATION_ID=? AND ORGANIZATION_TYPE='MARKETPLACE' FOR UPDATE",market);
  if(organization.isEmpty()||!"ACTIVE".equals(organization.getFirst().get("ORGANIZATION_STATUS")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Your marketplace is not accepting new loan requests.");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM CF_AUTH_USERS U WHERE USER_ROLE='MARKETPLACE' AND ACCOUNT_STATUS='ACTIVE' AND MARKETPLACE_ID=? AND NOT EXISTS (SELECT 1 FROM CF_AUTH_SERVICE_CLIENTS C WHERE C.USER_ID=U.USER_ID)",Integer.class,market)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Your marketplace is not accepting applications.");
  String id=UUID.randomUUID().toString(),at=OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSxxx"));
  var business=new LinkedHashMap<String,Object>(Map.of("displayName",profile.get("FULL_NAME"),"businessName",profile.get("BUSINESS_NAME"),"businessStartDate",profile.get("BUSINESS_START_DATE").toString().substring(0,10)));business.put("phase0",input.phase0());
  var sharing=Map.of("scope","APPLICATION_SHARE_WITH_CREDFLOW","noticeVersion","v1","authorisedAt",at,"evidenceReference",id);
  var payload=Map.of("marketplaceId",market,"marketplaceSellerId",profile.get("MARKETPLACE_SELLER_ID"),"marketplaceRequestId",id,"requestedAmount",amount.setScale(2).toPlainString(),"currency","INR","loanPurpose",input.loanPurpose(),"businessProfile",business,"sellerRequestReference",id,"initialSharingConsent",sharing);
  try{
   jdbc.update("INSERT INTO CF_MARKETPLACE_LOAN_REQUESTS (REQUEST_ID,SELLER_ID,SELLER_SUBJECT,MARKETPLACE_ID,IDEMPOTENCY_KEY,REQUEST_FINGERPRINT,REQUESTED_AMOUNT,LOAN_PURPOSE,SHARING_AUTHORISED_AT,NOTICE_VERSION,PAYLOAD_JSON,STATUS) VALUES (?,?,?,?,?,?,?,?,TO_TIMESTAMP_TZ(?,'YYYY-MM-DD\"T\"HH24:MI:SS.FFTZH:TZM'),'v1',?,'QUEUED')",id,sellerId,jwt.getSubject(),market,key,fingerprint,amount,input.loanPurpose(),at,mapper.writeValueAsString(payload));
  }catch(org.springframework.dao.DataIntegrityViolationException e){
   var winner=jdbc.queryForList("SELECT * FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE SELLER_ID=? AND IDEMPOTENCY_KEY=?",sellerId,key);
   if(winner.isEmpty())throw e;return replay(winner.getFirst(),fingerprint);
  }
  return read(id,sellerId);
 }
 private Map<String,Object> replay(Map<String,Object> row,String fingerprint){
  if(!fingerprint.equals(row.get("REQUEST_FINGERPRINT")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This retry key was already used with different loan details.");
  return present(row);
 }
 public Map<String,Object> read(String id,String seller){return present(jdbc.queryForMap("SELECT * FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE REQUEST_ID=? AND SELLER_ID=?",id,seller));}
 public Map<String,Object> list(Jwt jwt,boolean marketplace,int page,int size){
  if(page<0||size<1||size>100)throw new IllegalArgumentException("Invalid pagination.");
  String column=marketplace?"MARKETPLACE_ID":"SELLER_ID",value=identity(jwt,marketplace?"MARKETPLACE":"SELLER",marketplace?"marketplaceId":"sellerId");
  var rows=jdbc.queryForList("SELECT * FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE "+column+"=? ORDER BY CREATED_AT DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",value,(long)page*size,size);
  return Map.of("items",rows.stream().map(this::present).toList(),"totalElements",jdbc.queryForObject("SELECT COUNT(*) FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE "+column+"=?",Long.class,value));
 }
 private Map<String,Object> present(Map<String,Object> r){
  var result=new LinkedHashMap<String,Object>();
  result.put("requestId",r.get("REQUEST_ID"));result.put("requestedAmount",new BigDecimal(r.get("REQUESTED_AMOUNT").toString()).setScale(2).toPlainString());result.put("loanPurpose",r.get("LOAN_PURPOSE"));result.put("status",r.get("STATUS"));result.put("applicationId",r.get("APPLICATION_ID"));result.put("lastError",r.get("LAST_ERROR"));
  String id=r.get("REQUEST_ID").toString();var view=jdbc.queryForObject("SELECT PAYLOAD_JSON,CREATED_AT,SHARING_AUTHORISED_AT FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE REQUEST_ID=?",(rs,n)->Map.of("payload",rs.getString(1),"createdAt",rs.getObject(2,OffsetDateTime.class).toString(),"sharingAt",rs.getObject(3,OffsetDateTime.class).toString()),id);
  String payload=view.get("payload");result.put("createdAt",view.get("createdAt"));result.put("initialSharingAuthorisedAt",view.get("sharingAt"));
  result.put("businessProfile",mapper.readTree(payload).get("businessProfile"));return result;
 }
}
