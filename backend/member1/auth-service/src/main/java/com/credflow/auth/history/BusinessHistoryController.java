package com.credflow.auth.history;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/marketplace/sellers/{sellerId}/business-history")
public class BusinessHistoryController {
 private final BusinessHistoryService service;public BusinessHistoryController(BusinessHistoryService service){this.service=service;}
 @GetMapping public Object read(@PathVariable UUID sellerId,@AuthenticationPrincipal Jwt jwt){return service.read(sellerId.toString(),jwt);}
 @PostMapping public Object save(@PathVariable UUID sellerId,@Valid @RequestBody BusinessHistoryInput input,@RequestHeader("Idempotency-Key") UUID key,@AuthenticationPrincipal Jwt jwt){return service.save(sellerId.toString(),input,key.toString(),jwt);}
}
