package com.credflow.auth.marketplace;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
public class LoanRequestController {
 private final LoanRequestService service;
 public LoanRequestController(LoanRequestService service){this.service=service;}
 @PostMapping("/api/v1/seller/loan-requests") public Object submit(@Valid @RequestBody LoanRequestInput input,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.accepted().body(service.submit(input,key,jwt));}
 @GetMapping("/api/v1/seller/loan-requests") public Object seller(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(jwt,false,page,size);}
 @GetMapping("/api/v1/marketplace/loan-requests") public Object marketplace(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(jwt,true,page,size);}
}
