package com.credflow.application.outbox;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.application.entity.OutboxEventEntity;
import com.credflow.application.repository.OutboxEventRepository;

@Service
public class OutboxTransactionService {

    private static final int CANDIDATE_LIMIT = 20;
    private static final long LEASE_SECONDS = 300;

    private final OutboxEventRepository repository;

    public OutboxTransactionService(
            OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedOutboxEvent> claimNext() {

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        var candidateIds = repository.findClaimCandidates(
                now,
                PageRequest.of(0, CANDIDATE_LIMIT)
        );

        for (Long id : candidateIds) {

            String claimToken = UUID.randomUUID().toString();

            int updated = repository.tryClaim(
                    id,
                    claimToken,
                    now,
                    now.plusSeconds(LEASE_SECONDS)
            );

            if (updated == 0) {
                continue;
            }

            OutboxEventEntity event = repository.findById(id)
                    .orElseThrow(() -> new IllegalStateException(
                            "Claimed outbox event was not found: " + id));

            return Optional.of(new ClaimedOutboxEvent(
                    event.getOutboxEventId(),
                    claimToken,
                    event.getEventId(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getEventPayload(),
                    event.getRetryCount()
            ));
        }

        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(ClaimedOutboxEvent event) {

        return repository.markPublishedIfOwned(
                event.outboxEventId(),
                event.claimToken(),
                OffsetDateTime.now(ZoneOffset.UTC)
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(ClaimedOutboxEvent event) {

        // Retry after 5, 10, 20, 40... seconds, capped at 300.
        int exponent = Math.min(event.retryCount(), 6);
        long delaySeconds = Math.min(300L, 5L * (1L << exponent));

        OffsetDateTime nextAttemptAt =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plusSeconds(delaySeconds);

        return repository.markFailedIfOwned(
                event.outboxEventId(),
                event.claimToken(),
                nextAttemptAt
        ) == 1;
    }
}