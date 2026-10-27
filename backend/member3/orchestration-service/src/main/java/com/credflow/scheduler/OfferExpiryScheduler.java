package com.credflow.scheduler;

import com.credflow.service.WorkflowService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler is local to this application's process and datasource.
 */
@Component
public class OfferExpiryScheduler {

    private final WorkflowService service;

    public OfferExpiryScheduler(WorkflowService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${credflow.offer-expiry-delay-ms:5000}")
    public void tick() {
        service.expireOffers();
    }
}
