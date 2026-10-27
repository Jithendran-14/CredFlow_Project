package com.credflow.eligibility.api;
import jakarta.validation.Valid;import java.util.UUID;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;
@RestController @RequestMapping("/api/v1/lender-products")
public class LenderRulesController {
 private final LenderRulesService service;public LenderRulesController(LenderRulesService service){this.service=service;}
 @GetMapping public Object list(@AuthenticationPrincipal Jwt jwt){return service.list(jwt);}
 @GetMapping("/{id}/rules/history")public Object history(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return service.history(id,jwt);}
 @PutMapping("/{id}/rules")public Object update(@PathVariable UUID id,@Valid @RequestBody LenderRulesInput input,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt){return service.update(id,input,key,jwt);}
}
