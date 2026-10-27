package com.credflow.platform;

import com.credflow.platform.entity.DeliveryRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.bind.annotation.*;

@Component
@ConditionalOnProperty(name = "credflow.events.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryEngine {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeliveryEngine.class);

    private final Platform p;

    private final KafkaTemplate<String, String> kafka;

    private final List<Platform.Handler> handlers;

    private final List<Platform.JobObserver> observers;

    @PersistenceContext
    EntityManager em;

    public DeliveryEngine(
        Platform p,
        KafkaTemplate<String, String> kafka,
        List<Platform.Handler> handlers,
        List<Platform.JobObserver> observers
    ) {
        this.p = p;
        this.kafka = kafka;
        this.handlers = handlers;
        this.observers = observers;
    }

    @Bean
    DefaultErrorHandler kafkaErrors(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000, 3));
    }

    @KafkaListener(topics = "#{'${credflow.topics}'.split(',')}", groupId = "${credflow.service-name}")
    public void receive(ConsumerRecord<String, String> record) {
        var event = p.read(p.readTree(record.value()), Platform.Event.class);
        Platform.check(event.eventId() != null && event.applicationId() != null && event.applicationId().toString().equals(record.key()) && event.aggregateVersion() > 0 && "1.0".equals(event.schemaVersion()) && record.topic().equals(event.eventType()) && event.occurredAt() != null && validActor(event.actor()) && (event.originActor() == null || validActor(event.originActor())) && event.data() != null && event.data().isObject() && event.producer() != null && event.correlationId() != null && event.correlationId().matches("[A-Za-z0-9._:-]{1,100}"), 400, "INVALID_EVENT_ENVELOPE");
        p.tx(() -> {
            p.add("INBOX", event.eventId(), event.applicationId(), record.topic(), p.write(event));
            return null;
        });
        log.info("event_received eventId={} type={} applicationId={}", event.eventId(), event.eventType(), event.applicationId());
    }

    private static boolean validActor(Platform.Actor actor) {
        return actor != null && actor.type() != null && Set.of("HUMAN", "SYSTEM", "SYSTEM_CLIENT").contains(actor.type()) && actor.subject() != null && !actor.subject().isBlank();
    }

    private record Claimed(String id, String kind, UUID app, String target, String payload, UUID token) {
    }

    @Scheduled(fixedDelayString = "${credflow.delivery.poll-ms:1000}")
    public void run() {
        List<String> ids = p.tx(() -> em.createQuery("select r.id from DeliveryRow r where " + "((r.status='PENDING' and r.nextAttempt<=:now) " + "or (r.status='RUNNING' and r.leaseUntil<:now)) " + "and (r.kind in ('INBOX','JOB') or not exists " + "(select x.id from DeliveryRow x where " + "x.applicationId=r.applicationId " + "and x.kind=r.kind and x.position<r.position " + "and x.status<>'DONE')) " + "order by r.position", String.class).setParameter("now", Instant.now()).setMaxResults(30).getResultList());
        for (String id : ids) {
            Claimed row = p.tx(() -> claim(id));
            if (row == null) {
                continue;
            }
            try {
                if ("INBOX".equals(row.kind())) {
                    apply(row);
                } else {
                    JsonNode jobResponse = null;
                    if ("JOB".equals(row.kind())) {
                        var job = p.read(p.readTree(row.payload()), Platform.Job.class);
                        boolean permitted = p.tx(() -> {
                            DeliveryRow current = owned(row);
                            if (current == null) {
                                return false;
                            }
                            UUID jobId = UUID.fromString(current.getId().substring(4));
                            boolean allowed = observers.stream().allMatch(observer -> observer.canRunJob(current.getApplicationId(), jobId, job));
                            if (!allowed) {
                                done(current);
                                current.setError("OBSOLETE_JOB");
                                notifyJob(current, "SKIPPED");
                            }
                            return allowed;
                        });
                        if (!permitted) {
                            continue;
                        }
                        String base = p.environment().getRequiredProperty("credflow.urls." + job.target());
                        JsonNode wirePayload = job.payload();
                        if ("eligibility".equals(job.target())) {
                            ObjectNode exact = (ObjectNode) wirePayload.deepCopy();
                            exact.remove(List.of("correlationId", "causationId"));
                            wirePayload = exact;
                        }
                        jobResponse = p.call("system", HttpMethod.POST, base + job.path(), wirePayload, job.key(), job.correlationId());
                    } else {
                        kafka.send(row.target(), row.app().toString(), row.payload()).get(12, TimeUnit.SECONDS);
                    }
                    JsonNode response = jobResponse;
                    p.tx(() -> {
                        DeliveryRow current = owned(row);
                        if (current != null) {
                            if ("JOB".equals(current.getKind())) {
                                var job = p.read(current.getPayload(), Platform.Job.class);
                                UUID jobId = UUID.fromString(current.getId().substring(4));
                                p.jobContext(job, () -> observers.forEach(observer -> observer.onJobResponse(current.getApplicationId(), jobId, job, response)));
                            }
                            done(current);
                            notifyJob(current, "DONE");
                        }
                        return null;
                    });
                }
                log.info("delivery_completed id={} kind={} applicationId={}", row.id(), row.kind(), row.app());
            } catch (Exception failure) {
                log.warn("delivery_failed id={} kind={} applicationId={} error={}", row.id(), row.kind(), row.app(), failure.getClass().getSimpleName(), failure);
                p.tx(() -> {
                    DeliveryRow current = owned(row);
                    if (current == null) {
                        return null;
                    }
                    current.incrementAttempts();
                    current.setError(failure instanceof Platform.Pending ? failure.getMessage() : failure.getClass().getSimpleName());
                    int max = p.environment().getProperty("credflow.delivery.max-attempts", Integer.class, 20);
                    current.setStatus(current.getAttempts() >= max ? "DEAD" : "PENDING");
                    current.setNextAttempt(Instant.now().plusSeconds(Math.min(60, 1L << Math.min(6, current.getAttempts()))));
                    current.setLeaseUntil(null);
                    current.setLeaseToken(null);
                    notifyJob(current, current.getStatus());
                    if ("DEAD".equals(current.getStatus()) && "INBOX".equals(current.getKind())) {
                        p.add("DLT", UUID.randomUUID(), current.getApplicationId(), current.getTarget() + ".DLT", current.getPayload());
                    }
                    return null;
                });
            }
        }
    }

    private void notifyJob(DeliveryRow row, String state) {
        if (!"JOB".equals(row.getKind())) {
            return;
        }
        var job = p.read(row.getPayload(), Platform.Job.class);
        UUID jobId = UUID.fromString(row.getId().substring(4));
        p.jobContext(job, () -> observers.forEach(observer -> observer.onJobState(row.getApplicationId(), jobId, job, state, row.getError())));
    }

    private Claimed claim(String id) {
        DeliveryRow row = em.find(DeliveryRow.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || "DONE".equals(row.getStatus()) || "DEAD".equals(row.getStatus())) {
            return null;
        }
        Instant now = Instant.now();
        if ("RUNNING".equals(row.getStatus()) && row.getLeaseUntil().isAfter(now)) {
            return null;
        }
        if ("PENDING".equals(row.getStatus()) && row.getNextAttempt().isAfter(now)) {
            return null;
        }
        if (("OUTBOX".equals(row.getKind()) || "DLT".equals(row.getKind())) && em.createQuery("select count(x) from DeliveryRow x where " + "x.applicationId=:app and x.kind=:kind " + "and x.position<:pos and x.status<>'DONE'", Long.class).setParameter("app", row.getApplicationId()).setParameter("kind", row.getKind()).setParameter("pos", row.getPosition()).getSingleResult() > 0) {
            return null;
        }
        row.setStatus("RUNNING");
        row.setLeaseUntil(now.plusSeconds(45));
        row.setLeaseToken(UUID.randomUUID());
        return new Claimed(row.getId(), row.getKind(), row.getApplicationId(), row.getTarget(), row.getPayload(), row.getLeaseToken());
    }

    private DeliveryRow owned(Claimed claim) {
        DeliveryRow row = em.find(DeliveryRow.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        return row != null && claim.token().equals(row.getLeaseToken()) ? row : null;
    }

    private void apply(Claimed claim) {
        p.tx(() -> {
            DeliveryRow row = owned(claim);
            if (row == null) {
                return null;
            }
            var event = p.read(p.readTree(claim.payload()), Platform.Event.class);
            p.setEventContext(event);
            try {
                for (var handler : handlers) {
                    handler.onEvent(event);
                }
                done(row);
            } finally {
                p.clearEventContext();
            }
            return null;
        });
    }

    private void done(DeliveryRow row) {
        row.setStatus("DONE");
        row.setLeaseUntil(null);
        row.setLeaseToken(null);
        row.setError(null);
    }
}

@RestController
@RequestMapping("/api/v1/internal/deliveries")
@ConditionalOnProperty(name = "credflow.events.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryController {

    private final Platform p;

    private final List<Platform.JobObserver> observers;

    @PersistenceContext
    EntityManager em;

    DeliveryController(Platform p, List<Platform.JobObserver> observers) {
        this.p = p;
        this.observers = observers;
    }

    @GetMapping
    Object list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "JOB") String kind, @RequestParam(defaultValue = "DEAD") String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        authorize(jwt);
        Platform.check(Set.of("JOB", "INBOX", "OUTBOX", "DLT").contains(kind), 400, "INVALID_DELIVERY_KIND");
        Platform.check(Set.of("PENDING", "RUNNING", "DONE", "DEAD").contains(status), 400, "INVALID_DELIVERY_STATUS");
        Platform.check(page >= 0 && size >= 1 && size <= 100 && (long) page * size <= Integer.MAX_VALUE, 400, "INVALID_PAGINATION");
        return p.tx(() -> {
            List<Map<String, Object>> items = em.createQuery("select r from DeliveryRow r where r.kind=:kind and r.status=:status order by r.position", DeliveryRow.class).setParameter("kind", kind).setParameter("status", status).setFirstResult(page * size).setMaxResults(size).getResultList().stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deliveryId", row.getId());
                item.put("operationId", row.getId().substring(row.getId().indexOf(':') + 1));
                item.put("kind", row.getKind());
                item.put("applicationId", row.getApplicationId());
                item.put("target", row.getTarget());
                item.put("status", row.getStatus());
                item.put("attempts", row.getAttempts());
                item.put("nextAttemptAt", row.getNextAttempt());
                if (row.getError() != null)
                    item.put("error", row.getError());
                return item;
            }).toList();
            Long total = em.createQuery("select count(r) from DeliveryRow r where r.kind=:kind and r.status=:status", Long.class).setParameter("kind", kind).setParameter("status", status).getSingleResult();
            return Map.of("items", items, "page", page, "size", size, "totalElements", total);
        });
    }

    @GetMapping("/{kind}/{id}")
    Object status(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind, @PathVariable UUID id) {
        authorize(jwt);
        return p.tx(() -> {
            var row = em.find(DeliveryRow.class, kind + ":" + id);
            Platform.check(row != null, 404, "DELIVERY_NOT_FOUND");
            return Map.of("status", row.getStatus(), "attempts", row.getAttempts(), "nextAttemptAt", row.getNextAttempt(), "error", Objects.toString(row.getError(), ""));
        });
    }

    @PostMapping("/{kind}/{id}/replay")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    Object replay(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind, @PathVariable UUID id) {
        authorize(jwt);
        return p.tx(() -> {
            var row = em.find(DeliveryRow.class, kind + ":" + id, LockModeType.PESSIMISTIC_WRITE);
            Platform.check(row != null, 404, "DELIVERY_NOT_FOUND");
            Platform.check("DEAD".equals(row.getStatus()), 409, "DELIVERY_NOT_DEAD");
            row.setStatus("PENDING");
            row.setAttempts(0);
            row.setNextAttempt(Instant.now());
            row.setError(null);
            if ("JOB".equals(row.getKind())) {
                var job = p.read(row.getPayload(), Platform.Job.class);
                p.jobContext(job, () -> observers.forEach(observer -> observer.onJobState(row.getApplicationId(), id, job, "REPLAYED", null)));
            }
            return Map.of("operationId", id, "status", "PENDING");
        });
    }

    private void authorize(Jwt jwt) {
        p.requireScope(jwt, "ops.replay");
        Platform.check(p.hasRole(jwt, "ADMIN"), 403, "ADMIN_REQUIRED");
    }
}
