package com.credflow.consent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import com.credflow.consent.client.OrchestrationConsentClient;

@Component
@ConditionalOnProperty(
        name = "credflow.consent-delivery.enabled",
        havingValue = "true"
)
public class ConsentDeliveryWorker {

    private static final Logger log =
            LoggerFactory.getLogger(ConsentDeliveryWorker.class);

    private final ConsentDeliveryStore store;
    private final OrchestrationConsentClient orchestration;

    public ConsentDeliveryWorker(
            ConsentDeliveryStore store,
            OrchestrationConsentClient orchestration) {

        this.store = store;
        this.orchestration = orchestration;
    }

    @Scheduled(
        fixedDelayString =
            "${credflow.consent-delivery.fixed-delay-ms:5000}"
    )
    public void deliverNext() {

        try {
            var candidate = store.claimNext();

            if (candidate.isEmpty()) {
                return;
            }

            var delivery = candidate.get();

            try {
                orchestration.apply(
                        delivery.applicationId(),
                        delivery.operationId(),
                        delivery.correlationId(),
                        delivery.payload()
                );

                store.markApplied(delivery);

            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();

                store.markFailed(
                        delivery,
                        status,
                        "ORCHESTRATION_HTTP_" + status
                );

                log.warn(
                        "Consent delivery failed: operationId={}, HTTP={}",
                        delivery.operationId(),
                        status
                );

            } catch (Exception exception) {
                store.markFailed(
                        delivery,
                        null,
                        exception.getClass().getSimpleName()
                );

                log.warn(
                        "Consent delivery failed: operationId={}, errorType={}",
                        delivery.operationId(),
                        exception.getClass().getSimpleName()
                );
            }

        } catch (Exception exception) {
            log.error(
                    "Consent delivery worker failed: errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}