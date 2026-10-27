package com.credflow.api;

import com.credflow.dto.InformationAnswersRequest;
import com.credflow.dto.OfferActionRequest;
import com.credflow.dto.RevisionGuardRequest;
import com.credflow.dto.WorkflowVersionRequest;
import com.credflow.platform.Platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP mapping and parameter contract for WorkflowController.
 */
@RequestMapping("/api/v1")
public interface WorkflowApi {

    @GetMapping("/applications/{id}/workflow")
    public Object workflow(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);

    @GetMapping("/applications/{id}/offers")
    public Object offers(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @AuthenticationPrincipal Jwt jwt
    );

    @GetMapping("/applications/{id}/information-requests")
    public Object questions(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @AuthenticationPrincipal Jwt jwt
    );

    @GetMapping("/submissions")
    public Object submissions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String lenderId,
        @AuthenticationPrincipal Jwt jwt
    );

    @GetMapping("/submissions/{id}")
    public Object submission(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);

    @PostMapping("/offers/{id}/acceptance")
    public ResponseEntity<?> accept(
        @PathVariable UUID id,
        @Valid @RequestBody OfferActionRequest body,
        @RequestHeader("Idempotency-Key") String key,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/offers/{id}/decline")
    public ResponseEntity<?> decline(
        @PathVariable UUID id,
        @Valid @RequestBody OfferActionRequest body,
        @RequestHeader("Idempotency-Key") String key,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/information-requests/{id}/responses")
    public ResponseEntity<?> answer(
        @PathVariable UUID id,
        @Valid @RequestBody InformationAnswersRequest body,
        @RequestHeader("Idempotency-Key") String key,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/applications/{id}/cancellation")
    public ResponseEntity<?> cancel(
        @PathVariable UUID id,
        @Valid @RequestBody WorkflowVersionRequest body,
        @RequestHeader("Idempotency-Key") String key,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/applications/{id}/financial-refresh")
    public ResponseEntity<?> refresh(
        @PathVariable UUID id,
        @Valid @RequestBody WorkflowVersionRequest body,
        @RequestHeader("Idempotency-Key") String key,
        @AuthenticationPrincipal Jwt jwt
    );

    @GetMapping("/internal/submissions/{id}/authorization")
    public Object authorization(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);

    @PostMapping("/internal/applications/{id}/revision-guard")
    public Object correction(
        @PathVariable UUID id,
        @Valid @RequestBody RevisionGuardRequest body,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/internal/applications/{id}/revision-abort")
    public Object abort(
        @PathVariable UUID id,
        @Valid @RequestBody RevisionGuardRequest body,
        @AuthenticationPrincipal Jwt jwt
    );

    @PostMapping("/internal/applications/{id}/consent-operations")
    public Object consent(@PathVariable UUID id, @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt);
}
