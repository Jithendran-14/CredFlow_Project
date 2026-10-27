package com.credflow.eligibility.api;

import com.credflow.eligibility.EligibilityOperationStore;
import com.fasterxml.jackson.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class EligibilityOperationController {
    private final EligibilityOperationStore operations;
    private final EligibilityMachineAccess access;
    public EligibilityOperationController(EligibilityOperationStore operations,EligibilityMachineAccess access){this.operations=operations;this.access=access;}
    public record EvaluationRequest(@NotNull UUID evaluationRequestId,@NotNull UUID applicationId,
            @NotNull @Min(1) @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictRevision.class) Integer applicationRevision,
            @NotNull UUID verificationId,@NotNull LocalDate asOfDate,@NotBlank @Size(max=255) String authorisedRetrievalReference) {
        @JsonAnySetter public void unknownField(String name,com.fasterxml.jackson.databind.JsonNode value){throw new IllegalArgumentException("Unexpected evaluation field");}
    }
    public static class StrictRevision extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"Revision must be integer");return parser.getIntValue();
        }
    }
    public record Receipt(UUID operationId,String status,String statusUrl) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusResponse(UUID operationId,UUID applicationId,int applicationRevision,String status,
            String createdAt,UUID eligibilityResultId,String errorCode) {}

    @PostMapping("/internal/v1/eligibility/evaluations")
    @PreAuthorize("@eligibilityMachineAccess.allowed(authentication)")
    public ResponseEntity<Receipt> enqueue(@Valid @RequestBody EvaluationRequest body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        final UUID keyId;
        try {keyId=UUID.fromString(key);if(!keyId.toString().equalsIgnoreCase(key))throw new IllegalArgumentException();}
        catch(IllegalArgumentException ex){throw new InvalidOperationRequestException();}
        if(!keyId.equals(body.evaluationRequestId()))throw new EligibilityOperationStore.OperationConflictException();
        var saved=operations.enqueue(new EligibilityOperationStore.Command(body.evaluationRequestId(),body.applicationId(),body.applicationRevision(),
            body.verificationId(),body.asOfDate(),body.authorisedRetrievalReference()),access.clientId(),(String)request.getAttribute("correlationId"));
        // This is the stable original acceptance receipt, not a claim that processing completed.
        return ResponseEntity.accepted().body(new Receipt(saved.operationId(),"PENDING","/api/v1/operations/"+saved.operationId()));
    }
    @GetMapping("/api/v1/operations/{operationId}")
    @PreAuthorize("@eligibilityMachineAccess.allowed(authentication)")
    public StatusResponse read(@PathVariable UUID operationId) {
        var saved=operations.find(operationId,access.clientId()).orElseThrow(OperationNotFoundException::new);
        return new StatusResponse(saved.operationId(),saved.applicationId(),saved.applicationRevision(),saved.status(),saved.createdAt(),saved.eligibilityResultId(),saved.errorCode());
    }
    public static class InvalidOperationRequestException extends IllegalArgumentException {}
    public static class OperationNotFoundException extends RuntimeException {}
}
