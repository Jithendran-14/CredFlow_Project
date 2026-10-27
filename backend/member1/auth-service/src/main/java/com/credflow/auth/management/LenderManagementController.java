package com.credflow.auth.management;
import jakarta.validation.Valid;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/lenders")
public class LenderManagementController {
 private final LenderManagement service;public LenderManagementController(LenderManagement service){this.service=service;}
 @GetMapping public Object list(@AuthenticationPrincipal Jwt jwt){return service.list(jwt);}
 @PostMapping public ResponseEntity<?> create(@Valid @RequestBody LenderInput input,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.status(201).body(service.create(input,key,jwt));}
}
