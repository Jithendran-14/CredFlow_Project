package com.credflow.auth.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordRecoveryController {
	private final PasswordRecoveryService service;

	public PasswordRecoveryController(PasswordRecoveryService service) {
		this.service = service;
	}

	public record Forgot(@NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username) {
	}

	public record Reset(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String token,
			@NotBlank @Size(min = 12, max = 72) String password,
			@NotBlank @Size(min = 12, max = 72) String confirmPassword) {
	}

	@PostMapping("/forgot-password")
	public ResponseEntity<?> forgot(@Valid @RequestBody Forgot input, HttpServletRequest request) throws IOException {
		long start = System.nanoTime();
		try {
			service.request(input.username(), request.getRemoteAddr());
		} finally {
			// Keep a minimum response time while preserving explicit account errors.
			long remaining = TimeUnit.MILLISECONDS.toNanos(400) - (System.nanoTime() - start);
			if (remaining > 0)
				try {
					TimeUnit.NANOSECONDS.sleep(remaining);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
		}
		return ResponseEntity.accepted().header("Cache-Control", "no-store")
				.body(Map.of("message", PasswordRecoveryService.RECEIPT));
	}

	@PostMapping("/reset-password")
	public ResponseEntity<?> reset(@Valid @RequestBody Reset input, HttpServletRequest request) {
		service.reset(input.token(), input.password(), input.confirmPassword(), request.getRemoteAddr());
		return ResponseEntity.ok().header("Cache-Control", "no-store")
				.body(Map.of("message", "Your password has been changed. Sign in with your new password."));
	}
}
