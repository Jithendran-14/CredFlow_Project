package com.credflow.consent;
import com.credflow.consent.service.*;
import com.credflow.consent.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class IdentityPreparationMissingMappingTest {
 @Test void unconnectedIdentityIsActionRequiredRatherThanInfiniteRetry(){
  var store=mock(IdentityProcessingStore.class);var prechecks=mock(VerificationPrecheckService.class);var maps=mock(SellerIdentityMappingService.class);var validator=mock(Validator.class);var json=new ObjectMapper();var market=UUID.randomUUID();
  var req=new VerificationRequest(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1,new VerificationRequest.OriginActor("HUMAN","seller",market),"test-missing-history",null);
  var candidate=new IdentityProcessingStore.Candidate(req.verificationId().toString(),json.writeValueAsString(req));when(store.findReadyRequests()).thenReturn(List.of(candidate));when(validator.validate(any(VerificationRequest.class))).thenReturn(Set.of());when(prechecks.check(any())).thenReturn(VerificationPrecheckResult.ready("seller",market));when(maps.findActive("seller",market)).thenReturn(Optional.empty());
  new IdentityPreparationWorker(store,prechecks,maps,json,validator).prepare();var result=org.mockito.ArgumentCaptor.forClass(VerificationPrecheckResult.class);verify(store).recordPreparationProblem(eq(candidate),result.capture());assertEquals(VerificationPrecheckResult.Outcome.REJECTED,result.getValue().outcome());assertEquals("IDENTITY_MAPPING_REQUIRED",result.getValue().reasonCode());verify(store,never()).enqueue(any(),any(),anyString());
 }
}
