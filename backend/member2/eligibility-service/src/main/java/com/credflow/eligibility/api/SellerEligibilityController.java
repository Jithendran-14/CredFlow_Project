package com.credflow.eligibility.api;
import com.credflow.eligibility.*;
import com.credflow.eligibility.integration.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SellerEligibilityController {
 private final Member1Client client;private final ApplicationContractMapper mapper;private final EligibilityEvaluationService results;private final ObjectMapper json;private final EligibilityOperationStore operations;
 public SellerEligibilityController(Member1Client client,ApplicationContractMapper mapper,EligibilityEvaluationService results,ObjectMapper json,EligibilityOperationStore operations){this.client=client;this.mapper=mapper;this.results=results;this.json=json;this.operations=operations;}
 @GetMapping("/api/v1/applications/{id}/eligibility") @PreAuthorize("hasRole('SELLER')")
 public ResponseEntity<?> read(@PathVariable UUID id,@AuthenticationPrincipal Jwt seller,HttpServletRequest request) {
  String sellerId=seller.getClaimAsString("sellerId"),correlation=(String)request.getAttribute("correlationId");
  if(sellerId==null || sellerId.isBlank())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Seller identity is required");
  try {
   if(!mapper.ownsApplication(client.ownership(id,sellerId,correlation),id,sellerId))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found");
   var w=readJson(client.workflow(id,seller.getTokenValue(),correlation));
   if(!id.toString().equals(w.path("applicationId").asText()) || !w.path("applicationRevision").isIntegralNumber())throw new Member1Client.UpstreamUnavailable();
   if(!w.hasNonNull("eligibilityResultId")) {
    var availability=readJson(client.financialAvailability(id,seller.getTokenValue(),correlation));
    if(!id.toString().equals(availability.path("applicationId").asText()) || !availability.path("connected").isBoolean())throw new Member1Client.UpstreamUnavailable();
    if(!availability.path("connected").asBoolean())return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("applicationId",id,"status","NOT_AVAILABLE","workflowStatus",w.path("status").asText(),"assessmentStatus","NOT_STARTED","reasonCode","FINANCIAL_HISTORY_NOT_CONNECTED","message",availability.path("message").asText()));
    if(w.hasNonNull("eligibilityOperationId")) {
     var operation=operations.find(UUID.fromString(w.path("eligibilityOperationId").asText()),"orchestration-service");
     if(operation.isPresent() && id.equals(operation.get().applicationId()) && operation.get().applicationRevision()==w.path("applicationRevision").intValue() && "FAILED".equals(operation.get().status()))
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("applicationId",id,"status","BLOCKED","workflowStatus",w.path("status").asText(),"assessmentStatus","FAILED","reasonCode",Objects.toString(operation.get().errorCode(),"ELIGIBILITY_PROCESSING_FAILED"),"message","The assessment could not be completed. Please contact the project operator to review and retry the workflow."));
    }
    String state=w.path("status").asText();boolean blocked=Set.of("CHECK_FAILED","ACTION_REQUIRED","CANCELLED","REJECTED").contains(state);
    String message=blocked?"This application needs attention before an eligibility result is available.":state.equals("DRAFT")?"Your consent is required before the assessment can begin.":"Your eligibility assessment is being processed.";
    return ResponseEntity.status(blocked?HttpStatus.OK:HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(Map.of("applicationId",id,"status",blocked?"BLOCKED":"PENDING","message",message,"workflowStatus",state,"reasonCode",w.path("reasonCode").asText("")));
   }
   UUID resultId=UUID.fromString(w.path("eligibilityResultId").asText());var result=results.findById(resultId).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Workflow result has not reached this service"));
   if(!id.equals(result.applicationId()) || result.applicationRevision()!=w.path("applicationRevision").intValue()
      || !result.evaluationRequestId().toString().equals(w.path("evaluationRequestId").asText())
      || !result.verificationId().toString().equals(w.path("verificationId").asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Current result is not available");
   var current=mapper.application(client.application(id,correlation),id,result.applicationRevision(),result.asOfDate());
   if(!sellerId.equals(current.sellerId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found");
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(EligibilityResultResponse.from(result));
  }catch(Member1Client.UpstreamRejected ex){throw new ResponseStatusException(ex.status==403 || ex.status==404?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE,"Application result is unavailable");}
   catch(Member1Client.UpstreamUnavailable ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"A connected service is unavailable. Please retry.");}
   catch(ApplicationContractMapper.ApplicationContractException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Application details changed. Please refresh the assessment.");}
   catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The connected workflow returned an invalid result reference.");}
 }
 private JsonNode readJson(String text){try{var n=json.readTree(text);if(n==null || !n.isObject())throw new IllegalArgumentException();return n;}catch(Exception ex){throw new Member1Client.UpstreamUnavailable();}}
}
