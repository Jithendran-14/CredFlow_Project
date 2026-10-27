package com.credflow.consent.controller;
import java.util.*;
import com.credflow.consent.client.ApplicationOwnershipClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public class FinancialAvailabilityController {
 private final ApplicationOwnershipClient applications;private final JdbcTemplate jdbc;
 public FinancialAvailabilityController(ApplicationOwnershipClient applications,JdbcTemplate jdbc){this.applications=applications;this.jdbc=jdbc;}
 @GetMapping("/api/v1/applications/{id}/financial-data-availability")
 public ResponseEntity<?> read(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
  if(jwt==null || jwt.getClaimAsStringList("roles")==null || !jwt.getClaimAsStringList("roles").contains("SELLER") || jwt.getClaimAsString("sellerId")==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Seller identity is required");
  String seller=jwt.getClaimAsString("sellerId");applications.requireOwnership(id,seller);
  boolean connected=jdbc.queryForObject("SELECT COUNT(*) FROM CF_FINANCIAL_PROVIDER_MAP WHERE SELLER_ID=? AND MAPPING_STATUS='ACTIVE'",Integer.class,seller)>0;
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("applicationId",id,"connected",connected,"reasonCode",connected?"":"FINANCIAL_HISTORY_NOT_CONNECTED","message",connected?"Financial source connected.":"No business financial history is connected to your seller account. Your marketplace must link your actual financial records before eligibility can be assessed. This application is not being assessed yet."));
 }
}
