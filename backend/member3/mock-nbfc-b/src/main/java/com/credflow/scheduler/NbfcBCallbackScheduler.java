package com.credflow.scheduler;

import com.credflow.service.NbfcBSimulatorService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler is local to this application's process and datasource.
 */
@Component
public class NbfcBCallbackScheduler {

    private final NbfcBSimulatorService service;

    public NbfcBCallbackScheduler(NbfcBSimulatorService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${credflow.simulator.callback-poll-ms:1000}")
    public void tick() {
        service.callbacks();
    }
}
