package com.credflow.eligibility;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="credflow.eligibility.worker-enabled",havingValue="true")
public class EligibilityWorker {
 private final EligibilityOperationProcessor processor;
 public EligibilityWorker(DatabaseAccess database,TransactionTemplate tx,EligibilityTrustedPipeline pipeline,
   TrustedEligibilityContextProvider provider,@Value("${credflow.eligibility.worker-max-attempts:5}")int attempts,
   @Value("${credflow.eligibility.worker-lease-seconds:120}")int lease) {
  processor=new EligibilityOperationProcessor(database,tx,pipeline,provider,attempts,lease);
 }
 @Scheduled(fixedDelayString="${credflow.eligibility.worker-interval-ms:2000}")
 public void process(){processor.processNext();}
 @Configuration @EnableScheduling
 @ConditionalOnProperty(name="credflow.eligibility.worker-enabled",havingValue="true")
 static class Scheduling {}
}
