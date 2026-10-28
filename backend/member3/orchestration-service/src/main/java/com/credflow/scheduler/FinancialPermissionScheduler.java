package com.credflow.scheduler;

import com.credflow.service.WorkflowService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class FinancialPermissionScheduler {
	private static final Logger log = LoggerFactory.getLogger(FinancialPermissionScheduler.class);
	private final WorkflowService service;

	public FinancialPermissionScheduler(WorkflowService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${credflow.financial-permission-delay-ms:5000}")
	public void tick() {
		for (var id : service.pendingFinancialPermissions()) {
			try {
				service.reconcileFinancialPermission(id);
			} catch (RuntimeException ex) {
				log.warn("Financial permission reconciliation pending applicationId={} errorType={}", id,
						ex.getClass().getSimpleName());
			}
		}
	}
}
