package com.credflow.auth.recovery;

import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PasswordRecoveryController.class)
public class RecoveryErrors {
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<?> status(ResponseStatusException ex) {
		return error(ex.getStatusCode().value(), ex.getReason(), null);
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
	public ResponseEntity<?> validation(Exception ex) {
		return error(400,
				"Check the username, reset link and password fields. Passwords must contain 12 to 72 characters.",
				null);
	}

	@ExceptionHandler({ MailException.class, IOException.class })
	public ResponseEntity<?> mail(Exception ex) {
		return error(503,
				"Reset email could not be delivered. Start the project to make Mailpit available, then request a new link.",
				ex);
	}

	@ExceptionHandler({ DataAccessException.class, org.springframework.transaction.TransactionException.class })
	public ResponseEntity<?> database(Exception ex) {
		return error(503, "Account recovery is temporarily unavailable. Please try again later.", ex);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<?> unexpected(Exception ex) {
		return error(500, "The password reset could not be completed. Please try again later.", ex);
	}

	private ResponseEntity<?> error(int status, String message, Exception ex) {
		String reference = UUID.randomUUID().toString();
		// Exception messages/stack traces can contain SQL parameters; never log secrets
		// here.
		if (ex != null)
			LoggerFactory.getLogger(RecoveryErrors.class).warn("Recovery failure {} ({})", reference,
					ex.getClass().getSimpleName());
		var response = ResponseEntity.status(status).header("Cache-Control", "no-store");
		if (status == 429)
			response.header("Retry-After", "900");
		String code = switch (status) {
		case 400 -> "RESET_INPUT_INVALID";
		case 404 -> "RESET_USER_NOT_FOUND";
		case 403 -> "RESET_ACCOUNT_UNAVAILABLE";
		case 429 -> "RESET_RATE_LIMITED";
		case 503 -> "RESET_UNAVAILABLE";
		default -> "RESET_FAILED";
		};
		return response.body(Map.of("code", code, "message", message, "correlationId", reference));
	}
}
