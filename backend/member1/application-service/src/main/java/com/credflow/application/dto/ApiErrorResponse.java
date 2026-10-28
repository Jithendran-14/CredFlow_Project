package com.credflow.application.dto;

import java.util.List;

public record ApiErrorResponse(String code, String message, String correlationId, List<FieldError> fieldErrors) {
	public record FieldError(String field, String message) {
	}
}