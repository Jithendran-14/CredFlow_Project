package com.credflow.eligibility.api;

import com.credflow.eligibility.EligibilityOperationStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=EligibilityOperationController.class)
public class EligibilityOperationErrors {
    public record ErrorBody(String code,String message,String correlationId,List<Object> fieldErrors) {}
    private ResponseEntity<ErrorBody> error(HttpStatus status,String code,String message,HttpServletRequest request){return ResponseEntity.status(status).body(new ErrorBody(code,message,(String)request.getAttribute("correlationId"),List.of()));}
    @ExceptionHandler(EligibilityOperationStore.OperationConflictException.class)
    public ResponseEntity<ErrorBody> conflict(HttpServletRequest request){return error(HttpStatus.CONFLICT,"EVALUATION_INPUT_CONFLICT","This evaluation request was already used with different inputs.",request);}
    @ExceptionHandler(EligibilityOperationController.OperationNotFoundException.class)
    public ResponseEntity<ErrorBody> missing(HttpServletRequest request){return error(HttpStatus.NOT_FOUND,"OPERATION_NOT_FOUND","Operation not found.",request);}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,EligibilityOperationController.InvalidOperationRequestException.class})
    public ResponseEntity<ErrorBody> invalid(HttpServletRequest request){return error(HttpStatus.BAD_REQUEST,"INVALID_EVALUATION_REQUEST","Provide valid evaluation fields and an Idempotency-Key matching evaluationRequestId.",request);}
}
