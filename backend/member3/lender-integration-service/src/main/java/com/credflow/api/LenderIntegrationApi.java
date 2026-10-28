package com.credflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP mapping and parameter contract for LenderIntegrationController.
 */
public interface LenderIntegrationApi {

	@PostMapping("/api/v1/internal/submissions")
	public ResponseEntity<JsonNode> submit(@RequestBody JsonNode request, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@GetMapping("/api/v1/internal/submissions/{id}")
	public JsonNode dispatch(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);

	@PostMapping("/api/v1/internal/submissions/{id}/{operation}")
	public ResponseEntity<JsonNode> internalCommand(@PathVariable UUID id, @PathVariable String operation,
			@RequestBody JsonNode data, @RequestHeader("Idempotency-Key") UUID commandId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@PostMapping("/api/v1/submissions/{id}/{operation}")
	public ResponseEntity<JsonNode> officerCommand(@PathVariable UUID id, @PathVariable String operation,
			@RequestBody JsonNode data, @RequestHeader("Idempotency-Key") UUID commandId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@PostMapping("/api/v1/lenders/{lenderId}/callbacks")
	public ResponseEntity<JsonNode> callback(@PathVariable String lenderId, @RequestBody JsonNode body,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@PostMapping("/api/v1/internal/integration-tasks/{id}/replay")
	public ResponseEntity<JsonNode> replay(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);

	@GetMapping("/api/v1/internal/integration-tasks/{id}")
	public JsonNode task(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt);
}
