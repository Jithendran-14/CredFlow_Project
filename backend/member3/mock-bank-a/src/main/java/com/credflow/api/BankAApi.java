package com.credflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP mapping and parameter contract for BankAController.
 */
@RequestMapping("/mock/lenders/{bank}")
public interface BankAApi {

	@PostMapping({ "/applications", "/cases" })
	public Object submit(@PathVariable String bank, @RequestBody JsonNode wire, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@GetMapping({ "/applications/by-reference/{reference}", "/cases/reference/{reference}" })
	public Object lookup(@PathVariable String bank, @PathVariable UUID reference, @AuthenticationPrincipal Jwt jwt);

	@PostMapping({ "/applications/by-reference/{reference}/cancellation", "/cases/reference/{reference}/withdraw" })
	public Map<String, Object> cancelReference(@PathVariable String bank, @PathVariable UUID reference,
			@RequestBody(required = false) JsonNode body, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@GetMapping({ "/applications/{externalId}", "/cases/{externalId}" })
	public JsonNode details(@PathVariable String bank, @PathVariable String externalId,
			@AuthenticationPrincipal Jwt jwt);

	@PostMapping({ "/applications/{externalId}/commands/{commandId}", "/cases/{externalId}/commands/{commandId}" })
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, String> command(@PathVariable String bank, @PathVariable String externalId,
			@PathVariable UUID commandId, @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-ID", required = false) String correlation);

	@PostMapping("/callbacks/{eventId}/replay")
	public Map<String, String> replay(@PathVariable String bank, @PathVariable UUID eventId,
			@AuthenticationPrincipal Jwt jwt);
}
