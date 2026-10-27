package com.credflow.scheduler;
import com.credflow.platform.Platform;
import com.credflow.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EligibilityStatusSchedulerTest {
 private final WorkflowService service=mock(WorkflowService.class);
 private final Platform p=mock(Platform.class);
 private final WorkflowService.PendingEligibility pending=new WorkflowService.PendingEligibility(UUID.randomUUID(),UUID.randomUUID(),2);
 private final EligibilityStatusScheduler scheduler=new EligibilityStatusScheduler(service,p,"http://localhost:8083");
 private void reply(String status,UUID app,int revision)throws Exception {
  when(service.pendingEligibility()).thenReturn(List.of(pending));
  when(p.call(eq("eligibility"),any(),anyString(),isNull(),isNull(),anyString())).thenReturn(new ObjectMapper().valueToTree(Map.of("operationId",pending.operationId(),"applicationId",app,"applicationRevision",revision,"status",status)));
 }
 @Test void failedReceiptPausesMatchingApplication()throws Exception {reply("FAILED",pending.applicationId(),2);scheduler.tick();verify(service).eligibilityProcessingFailed(pending);}
 @Test void runningOrSuccessfulOperationIsNotMarkedFailed()throws Exception {for(String s:List.of("PENDING","RUNNING","SUCCEEDED")){reply(s,pending.applicationId(),2);scheduler.tick();}verify(service,never()).eligibilityProcessingFailed(any());}
 @Test void mismatchedApplicationAndStaleRevisionAreIgnored()throws Exception {reply("FAILED",UUID.randomUUID(),2);scheduler.tick();reply("FAILED",pending.applicationId(),1);scheduler.tick();verify(service,never()).eligibilityProcessingFailed(any());}
 @Test void temporaryNetworkFailureDoesNotInventLenderRejection() {when(service.pendingEligibility()).thenReturn(List.of(pending));when(p.call(eq("eligibility"),any(),anyString(),isNull(),isNull(),anyString())).thenThrow(new IllegalStateException("unavailable"));scheduler.tick();verify(service,never()).eligibilityProcessingFailed(any());}
}
