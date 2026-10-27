package com.credflow.application.correction;
import java.util.UUID;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.http.*;
@RestController @RequestMapping("/api/v1/applications/{id}/corrections")
public class CorrectionController {
 private final CorrectionService service;public CorrectionController(CorrectionService service){this.service=service;}
 @PostMapping public ResponseEntity<?> submit(@PathVariable UUID id,@Valid @RequestBody CorrectionInput input,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.accepted().body(service.submit(id,input,key,jwt));}
 @GetMapping public Object list(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return service.list(id,jwt);}
}
