package com.credflow.eligibility.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/routing-priorities")
public class RoutingPriorityController {
    private final LenderRulesService service;
    public RoutingPriorityController(LenderRulesService service){this.service=service;}
    @GetMapping public Object list(@AuthenticationPrincipal Jwt jwt){return service.routingPriorities(jwt);}
    @PutMapping("/{id}") public Object update(@PathVariable UUID id,@Valid @RequestBody RoutingPriorityInput input,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt){
        return service.updateRoutingPriority(id,input,key,jwt);
    }
}
