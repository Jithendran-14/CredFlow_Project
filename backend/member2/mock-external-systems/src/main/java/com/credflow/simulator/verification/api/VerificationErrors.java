package com.credflow.simulator.verification.api;

import com.credflow.simulator.verification.VerificationSimulator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=VerificationController.class)
public class VerificationErrors {
    public record ErrorBody(String code, String message, String correlationId, List<Object> fieldErrors) {}
    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message,
            (String) request.getAttribute("correlationId"), List.of()));
    }
    @ExceptionHandler(VerificationSimulator.RequestConflictException.class)
    public ResponseEntity<ErrorBody> conflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "VERIFICATION_REQUEST_CONFLICT",
            "This verification request was already used with different inputs.", request);
    }
    @ExceptionHandler(VerificationSimulator.UnknownFixtureException.class)
    public ResponseEntity<ErrorBody> unknown(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_VERIFICATION_FIXTURE", "Unknown fictional verification identity.", request);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> invalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_REQUEST", "Provide valid verification request fields.", request);
    }
}
