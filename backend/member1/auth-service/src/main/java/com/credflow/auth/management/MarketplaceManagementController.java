package com.credflow.auth.management;
import jakarta.validation.Valid;import java.util.UUID;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/marketplaces")
public class MarketplaceManagementController {
 private final MarketplaceManagement service;public MarketplaceManagementController(MarketplaceManagement service){this.service=service;}
 @GetMapping public Object list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(jwt,page,size);}
 @GetMapping("/{id}") public Object read(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return service.read(id.toString(),jwt);}
 @PostMapping public ResponseEntity<?> create(@Valid @RequestBody MarketplaceInput input,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.status(201).body(service.create(input,key,jwt));}
 @PutMapping("/{id}") public Object update(@PathVariable UUID id,@Valid @RequestBody MarketplaceUpdate input,@AuthenticationPrincipal Jwt jwt){return service.update(id.toString(),input,jwt);}
 @DeleteMapping("/{id}") public Object delete(@PathVariable UUID id,@RequestParam long expectedVersion,@AuthenticationPrincipal Jwt jwt){return service.delete(id.toString(),expectedVersion,jwt);}
}
