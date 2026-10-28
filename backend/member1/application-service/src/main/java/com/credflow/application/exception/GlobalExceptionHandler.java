
package com.credflow.application.exception;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.credflow.application.dto.ApiErrorResponse;
import com.credflow.application.dto.ApiErrorResponse.FieldError;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ActiveApplicationException.class)
	public ResponseEntity<ApiErrorResponse> active(ActiveApplicationException ex, HttpServletRequest request) {
		return buildResponse(HttpStatus.CONFLICT, "ACTIVE_APPLICATION_EXISTS", ex.getMessage(), request,
				ex.applicationId() == null ? List.of()
						: List.of(new FieldError("existingApplicationId", ex.applicationId())));
	}

	@ExceptionHandler(ApplicationNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(ApplicationNotFoundException exception,
			HttpServletRequest request) {

		return buildResponse(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", exception.getMessage(), request, List.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {

		List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldError(error.getField(),
						error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
				.toList();

		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "One or more request fields are invalid",
				request, errors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception,
			HttpServletRequest request) {

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
				"Request body is invalid or contains unsupported values", request, List.of());
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException exception,
			HttpServletRequest request) {

		return buildResponse(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), request,
				List.of());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception,
			HttpServletRequest request) {

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
	}

	@ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
	public ResponseEntity<ApiErrorResponse> status(org.springframework.web.server.ResponseStatusException ex,
			HttpServletRequest request) {
		return buildResponse(HttpStatus.valueOf(ex.getStatusCode().value()), "REQUEST_REJECTED", ex.getReason(),
				request, List.of());
	}

	private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, String code, String message,
			HttpServletRequest request, List<FieldError> fieldErrors) {

		String correlationId = request.getHeader("X-Correlation-ID");

		if (correlationId == null || correlationId.isBlank()) {
			correlationId = UUID.randomUUID().toString();
		}

		ApiErrorResponse body = new ApiErrorResponse(code, message, correlationId, fieldErrors);

		return ResponseEntity.status(status).body(body);
	}
}
