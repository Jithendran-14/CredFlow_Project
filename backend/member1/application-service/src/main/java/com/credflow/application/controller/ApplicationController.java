
package com.credflow.application.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.credflow.application.dto.ApplicationRequest;
import com.credflow.application.dto.ApplicationResponse;
import com.credflow.application.service.ApplicationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> createApplication(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApplicationRequest request,
            @AuthenticationPrincipal Jwt jwt) {

    	ApplicationResponse response =
    	        applicationService.createApplication(
    	                request, idempotencyKey, jwt);

        URI location = URI.create(
                "/api/v1/applications/" + response.applicationId());

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public Object list(@org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,
                       @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int size,
                       @AuthenticationPrincipal Jwt jwt) {
        return applicationService.list(page, size, jwt);
    }

    @GetMapping("/active")
    public Object active(@AuthenticationPrincipal Jwt jwt,@org.springframework.web.bind.annotation.RequestParam(required=false) String purpose){return applicationService.activeApplication(jwt,purpose);}

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationResponse> getApplication(
            @PathVariable String applicationId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID.fromString(applicationId);

        ApplicationResponse response =
                applicationService.getApplication(applicationId, jwt);

        return ResponseEntity.ok(response);
    }
}
