package com.credflow.auth.controller;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.credflow.auth.dto.SellerRegistrationRequest;
import com.credflow.auth.service.SellerRegistrationService;

@RestController
public class SellerRegistrationController {
	private final SellerRegistrationService service;

	public SellerRegistrationController(SellerRegistrationService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/auth/marketplaces")
	public Object marketplaces() {
		return service.marketplaces();
	}

	@GetMapping("/api/v1/auth/lenders")
	public Object lenders() {
		return service.lenders();
	}

	@PostMapping("/api/v1/auth/register-seller")
	public ResponseEntity<?> register(@Valid @RequestBody SellerRegistrationRequest r) {
		return ResponseEntity.status(201).body(service.register(r));
	}

	@GetMapping("/api/v1/seller/profile")
	public Object profile(@AuthenticationPrincipal Jwt jwt) {
		return service.profile(jwt);
	}

	@GetMapping("/api/v1/marketplace/sellers")
	public Object sellers(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return service.directory(jwt, page, size);
	}
}
