package com.credflow.consent.controller;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.credflow.consent.repository.ConsentRepository;
import com.credflow.consent.client.ApplicationOwnershipClient;
import tools.jackson.databind.ObjectMapper;
@RestController
public class ConsentReadController {
 private final ConsentRepository records; private final ApplicationOwnershipClient applications; private final ObjectMapper mapper; private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 public ConsentReadController(ConsentRepository records,ApplicationOwnershipClient applications,ObjectMapper mapper,org.springframework.jdbc.core.JdbcTemplate jdbc){this.jdbc=jdbc;this.records=records;this.applications=applications;this.mapper=mapper;}
 @GetMapping("/api/v1/applications/{id}/consents")
 public Object read(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
  applications.requireOwnership(id,jwt.getClaimAsString("sellerId"));
  var row=records.findByApplicationId(id.toString());
  if(row.isEmpty()) return Map.of("applicationId",id.toString(),"consentVersion",0,"status","NOT_RECORDED","orchestrationStatus","NOT_APPLIED");
  var c=row.get();var result=new LinkedHashMap<String,Object>();result.put("applicationId",id.toString());result.put("consentId",c.getConsentId());result.put("consentVersion",c.getConsentVersion());result.put("status",c.getConsentStatus());result.put("orchestrationStatus",c.getOrchestrationAppliedVersion().equals(c.getConsentVersion())?"APPLIED":"PENDING");result.put("notice",mapper.readTree(c.getConsentPayload()));
  int revision=applications.getContext(id).applicationRevision();
  var grants=jdbc.query("SELECT CONSENT_ID, CONSENT_VERSION, CONSENT_STATUS, EXPIRES_AT FROM CF_FINANCIAL_CONSENTS WHERE APPLICATION_ID=? AND APPLICATION_REVISION=? ORDER BY CONSENT_VERSION DESC FETCH FIRST 1 ROWS ONLY",(rs,n)->{
    var value=new LinkedHashMap<String,Object>();value.put("financialConsentId",rs.getString(1));value.put("financialConsentVersion",rs.getInt(2));var expires=rs.getObject(4,java.time.OffsetDateTime.class);String status=rs.getString(3);value.put("status","ACTIVE".equals(status)&&!expires.isAfter(java.time.OffsetDateTime.now())?"EXPIRED":status);value.put("expiresAt",expires.toString());return value;
  },id.toString(),revision);
  result.put("financialConsent",grants.isEmpty()?Map.of("financialConsentVersion",0,"status","NOT_RECORDED"):grants.getFirst());return result;
 }
}
