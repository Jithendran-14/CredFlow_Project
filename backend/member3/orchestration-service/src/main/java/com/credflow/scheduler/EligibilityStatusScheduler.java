package com.credflow.scheduler;

import com.credflow.platform.Platform;
import com.credflow.service.WorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poll trusted operation receipts outside workflow transactions; fence late
 * responses before applying.
 */
@Component
public class EligibilityStatusScheduler {
	private static final Logger log = LoggerFactory.getLogger(EligibilityStatusScheduler.class);
	private final WorkflowService service;
	private final Platform platform;
	private final String base;

	public EligibilityStatusScheduler(WorkflowService service, Platform platform,
			@Value("${credflow.urls.eligibility:http://localhost:8083}") String base) {
		this.service = service;
		this.platform = platform;
		this.base = base.replaceAll("/+$", "");
	}

	@Scheduled(fixedDelayString = "${credflow.eligibility-status-delay-ms:5000}")
	public void tick() {
		for (var pending : service.pendingEligibility()) {
			try {
				var response = platform.call("eligibility", HttpMethod.GET,
						base + "/api/v1/operations/" + pending.operationId(), null, null,
						pending.operationId().toString());
				if (response != null && "FAILED".equals(response.path("status").asText())
						&& pending.operationId().toString().equals(response.path("operationId").asText())
						&& pending.applicationId().toString().equals(response.path("applicationId").asText())
						&& pending.applicationRevision() == response.path("applicationRevision").asInt())
					service.eligibilityProcessingFailed(pending);
			} catch (RuntimeException error) {
				log.warn("Eligibility status check pending operationId={} errorType={}", pending.operationId(),
						error.getClass().getSimpleName());
			}
		}
	}
}
