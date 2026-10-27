package com.credflow.consent.controller;

import java.util.Map;
import java.util.UUID;
import com.credflow.consent.service.FinancialConsentEvents;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Only the authenticated orchestration machine can inspect permission for outbound actions. */
@RestController
public class FinancialPermissionController {
    private final FinancialConsentEvents events;
    public FinancialPermissionController(FinancialConsentEvents events){this.events=events;}
    @GetMapping("/internal/v1/applications/{id}/financial-permission")
    public ResponseEntity<Map<String,Object>> permission(@PathVariable UUID id,@RequestParam int revision){
        if(revision<1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_REVISION");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.current(id,revision));
    }
}
