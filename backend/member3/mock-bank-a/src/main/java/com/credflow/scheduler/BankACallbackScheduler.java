package com.credflow.scheduler;

import com.credflow.service.BankASimulatorService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler is local to this application's process and datasource.
 */
@Component
public class BankACallbackScheduler {

    private final BankASimulatorService service;

    public BankACallbackScheduler(BankASimulatorService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${credflow.simulator.callback-poll-ms:1000}")
    public void tick() {
        service.callbacks();
    }
}
