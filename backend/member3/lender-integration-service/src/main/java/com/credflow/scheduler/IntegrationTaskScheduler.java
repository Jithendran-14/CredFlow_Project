package com.credflow.scheduler;

import com.credflow.service.LenderIntegrationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler is local to this application's process and datasource.
 */
@Component
public class IntegrationTaskScheduler {

    private final LenderIntegrationService service;

    public IntegrationTaskScheduler(LenderIntegrationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${credflow.integration.poll-ms:1000}")
    public void tick() {
        service.work();
    }
}
