package com.credflow.consent.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;

/** Preserve controlled domain errors without forwarding to a protected /error route. */
@RestControllerAdvice
public class ConsentErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> controlled(ResponseStatusException error) {
        String code = error.getReason() == null ? "CONSENT_REQUEST_FAILED" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code", code, "message", code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
        var fields = new java.util.LinkedHashMap<String, String>();
        error.getBindingResult().getFieldErrors().forEach(field ->
            fields.putIfAbsent(field.getField(), field.getDefaultMessage() == null ? "Invalid value" : field.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED",
            "message", "Check the required consent details and try again.", "fieldErrors", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST_BODY",
            "message", "The consent request contains an invalid value or format."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException error) {
        return ResponseEntity.status(403).body(Map.of("code", "ACCESS_DENIED",
            "message", "You do not have permission to perform this consent operation."));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable(DataAccessException error) {
        return ResponseEntity.status(503).body(Map.of("code", "CONSENT_STORAGE_UNAVAILABLE",
            "message", "Consent storage is temporarily unavailable. Refresh to check the current permission before retrying."));
    }
}
