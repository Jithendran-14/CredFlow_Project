package com.credflow.application.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.credflow.application.dto.OwnershipCheckRequest;
import com.credflow.application.dto.OwnershipCheckResponse;

import jakarta.validation.Valid;

import com.credflow.application.dto.ApplicationContextResponse;
import com.credflow.application.service.ApplicationService;

@RestController
@RequestMapping("/internal/v1/applications")
public class InternalApplicationController {

    private final ApplicationService applicationService;

    public InternalApplicationController(
            ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/{applicationId}/context")
    public ResponseEntity<ApplicationContextResponse> getApplicationContext(
            @PathVariable("applicationId") String applicationId,
            @AuthenticationPrincipal Jwt jwt) {

        ApplicationContextResponse response =
                applicationService.getApplicationContext(
                        applicationId, jwt);

        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{applicationId}/ownership-check")
    public ResponseEntity<OwnershipCheckResponse> checkApplicationOwnership(
            @PathVariable("applicationId") String applicationId,
            @Valid @RequestBody OwnershipCheckRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        OwnershipCheckResponse response =
                applicationService.checkApplicationOwnership(
                        applicationId,
                        request.sellerId(),
                        jwt
                );

        return ResponseEntity.ok(response);
    }
}