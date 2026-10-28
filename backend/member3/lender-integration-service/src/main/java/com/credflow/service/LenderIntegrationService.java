package com.credflow.service;

import com.credflow.entity.IntegrationCallback;
import com.credflow.entity.IntegrationDispatch;
import com.credflow.entity.IntegrationTask;
import com.credflow.platform.LenderCommandPayloads;
import com.credflow.platform.Platform.Actor;
import com.credflow.platform.Platform.Event;
import com.credflow.platform.Platform;
import com.credflow.repository.IntegrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class LenderIntegrationService implements Platform.Handler {

	private static final Set<String> TYPES = Set.of("lender.information.requested", "lender.information.answered",
			"lender.decision.received", "loan.offer.created", "loan.offer.acknowledged", "loan.offer.withdrawn",
			"lender.cancellation.acknowledged");

	private final Platform p;

	private final IntegrationRepository repository;

	@Value("${credflow.urls.orchestration:http://localhost:8084}")
	String orchestrationUrl;

	@Value("${credflow.urls.bank-a:http://localhost:8091}")
	String bankAUrl;

	@Value("${credflow.urls.nbfc-b:http://localhost:8092}")
	String nbfcBUrl;

	@Value("${credflow.lenders.bank-a-id:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1}")
	String bankA;

	@Value("${credflow.lenders.nbfc-b-id:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2}")
	String nbfcB;

	@Value("${credflow.lenders.bank-a-callback-client:mock-bank-a}")
	String bankCallback;

	@Value("${credflow.lenders.nbfc-b-callback-client:mock-nbfc-b}")
	String nbfcCallback;

	@Value("${credflow.urls.bank-c:http://localhost:8093}")
	String bankCUrl = "http://localhost:8093";
	@Value("${credflow.lenders.bank-c-id:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3}")
	String bankC = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3";
	@Value("${credflow.lenders.bank-c-callback-client:mock-bank-c}")
	String bankCCallback = "mock-bank-c";

	public LenderIntegrationService(Platform p, IntegrationRepository repository) {
		this.repository = repository;
		this.p = p;
	}

	@Override
	public void onEvent(Event event) {
		if (!Set.of("loan.offer.accepted", "loan.offer.declined").contains(event.eventType())) {
			return;
		}
		if (!"orchestration-service".equals(event.producer())) {
			throw new IllegalArgumentException("UNTRUSTED_WORKFLOW_PRODUCER");
		}
		JsonNode data = event.data();
		UUID submissionId = uuid(data, "submissionId");
		IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, submissionId,
				LockModeType.PESSIMISTIC_WRITE);
		if (dispatch == null) {
			throw new Platform.Pending("SUBMISSION_NOT_RESERVED");
		}
		if (!dispatch.getApplicationId().equals(event.applicationId())
				|| dispatch.getApplicationRevision() != data.path("applicationRevision").asInt()
				|| !dispatch.getLenderId().equals(text(data, "lenderId"))) {
			conflict("WORKFLOW_COMMAND_DISPATCH_MISMATCH");
		}
		boolean accepted = "loan.offer.accepted".equals(event.eventType());
		String operation = accepted ? "ACCEPT_OFFER" : "DECLINE_OFFER";
		UUID id = uuid(data, accepted ? "acceptanceId" : "declineId");
		validateCommand(operation, data);
		ObjectNode command = data.deepCopy();
		command.put("correlationId", event.correlationId());
		command.put("causationId", event.eventId().toString());
		enqueue(submissionId, id, operation, commandEnvelope(operation, command, event.originActor()));
	}

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	org.springframework.jdbc.core.JdbcTemplate registry;

	private String dynamicCode(String lender) {
		if (registry != null) {
			var codes = registry.queryForList(
					"SELECT LENDER_CODE FROM CF_AUTH.CF_LENDER_REGISTRY WHERE LENDER_ID=? AND ADAPTER='BANK_A'",
					String.class, lender);
			if (codes.size() == 1)
				return codes.getFirst();
		}
		throw new IllegalArgumentException("UNKNOWN_LENDER");
	}

	public String callbackClient(String lender) {
		if (bankA.equals(lender)) {
			return bankCallback;
		}
		if (nbfcB.equals(lender)) {
			return nbfcCallback;
		}
		if (bankC.equals(lender))
			return bankCCallback;
		dynamicCode(lender);
		return bankCallback;
	}

	public String base(String lender) {
		callbackClient(lender);
		if (!bankA.equals(lender) && !nbfcB.equals(lender) && !bankC.equals(lender))
			return bankAUrl + "/mock/lenders/" + dynamicCode(lender) + "/applications";
		return bankC.equals(lender) ? bankCUrl + "/mock/lenders/bank-c/applications"
				: (bankA.equals(lender) ? bankAUrl : nbfcBUrl)
						+ (bankA.equals(lender) ? "/mock/lenders/bank-a/applications" : "/mock/lenders/nbfc-b/cases");
	}

	public String referencePath(IntegrationDispatch dispatch) {
		return base(dispatch.getLenderId()) + (!nbfcB.equals(dispatch.getLenderId()) ? "/by-reference/" : "/reference/")
				+ dispatch.getSubmissionId();
	}

	public String lender(UUID id) {
		return p.tx(() -> required(id, false).getLenderId());
	}

	public JsonNode status(UUID id) {
		return p.tx(() -> view(required(id, false)));
	}

	public JsonNode taskStatus(UUID id) {
		return p.tx(() -> {
			IntegrationTask task = repository.find(IntegrationTask.class, id);
			if (task != null) {
				ObjectNode result = taskMetadata(required(task.getSubmissionId(), false), task);
				result.put("status", task.getStatus());
				result.put("attempts", task.getAttempts());
				if (task.getFailureCode() != null)
					result.put("failureCode", task.getFailureCode());
				result.put("nextAttemptAt", task.getNextAttemptAt().toString());
				return result;
			}
			IntegrationCallback callback = repository.find(IntegrationCallback.class, id);
			if (callback == null) {
				throw new NoSuchElementException("TASK_NOT_FOUND");
			}
			ObjectNode result = metadata(required(callback.getSubmissionId(), false));
			result.put("taskId", callback.getEventId().toString());
			result.put("operation", "CALLBACK");
			result.put("status", callback.getStatus());
			result.put("attempts", callback.getAttempts());
			if (callback.getFailureCode() != null)
				result.put("failureCode", callback.getFailureCode());
			result.put("nextAttemptAt", callback.getNextAttemptAt().toString());
			return result;
		});
	}

	public JsonNode reserve(JsonNode request) {
		validateSubmission(request);
		UUID id = uuid(request, "submissionId");
		String hash = businessHash(request);
		try {
			return p.tx(() -> {
				IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, id,
						LockModeType.PESSIMISTIC_WRITE);
				if (dispatch != null) {
					return existingReservation(dispatch, request, hash);
				}
				dispatch = new IntegrationDispatch();
				dispatch.setSubmissionId(id);
				dispatch.setApplicationId(uuid(request, "applicationId"));
				dispatch.setApplicationRevision(request.path("applicationRevision").asInt());
				dispatch.setLenderId(text(request, "lenderId"));
				dispatch.setPayloadHash(hash);
				dispatch.setRequestJson(p.write(request));
				repository.save(dispatch);
				saveTask(id, id, "SUBMIT", request);
				repository.flush();
				return view(dispatch);
			});
		} catch (RuntimeException failure) {
			return p.tx(() -> {
				IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, id,
						LockModeType.PESSIMISTIC_WRITE);
				if (dispatch == null) {
					throw failure;
				}
				return existingReservation(dispatch, request, hash);
			});
		}
	}

	public JsonNode existingReservation(IntegrationDispatch dispatch, JsonNode request, String hash) {
		JsonNode stored = parse(dispatch.getRequestJson());
		boolean cancellationPlaceholder = dispatch.isCancellationRequested() && "CANCELLED".equals(dispatch.getStatus())
				&& stored.hasNonNull("cancellationId") && !stored.hasNonNull("financialSnapshotId");
		if (cancellationPlaceholder) {
			if (!dispatch.getApplicationId().equals(uuid(request, "applicationId"))
					|| dispatch.getApplicationRevision() != request.path("applicationRevision").asInt()
					|| !dispatch.getLenderId().equals(text(request, "lenderId"))) {
				conflict("CANCELLED_SUBMISSION_BINDING_MISMATCH");
			}
			// Retain the cancellation tombstone; acknowledge a delayed reserve
			// without creating a lender submission task.
			dispatch.setPayloadHash(hash);
			dispatch.setRequestJson(p.write(request));
		} else {
			same(dispatch.getPayloadHash(), hash);
		}
		return view(dispatch);
	}

	public void command(UUID id, UUID commandId, String type, JsonNode data, Actor origin) {
		validateCommand(type, data);
		ObjectNode envelope = commandEnvelope(type, data, origin);
		for (int attempt = 0;; attempt++) {
			try {
				p.tx(() -> {
					enqueue(id, commandId, type, envelope);
					repository.flush();
					return null;
				});
				return;
			} catch (RuntimeException failure) {
				boolean duplicate = p.tx(() -> {
					IntegrationTask existing = repository.find(IntegrationTask.class, commandId);
					if (existing == null) {
						return false;
					}
					same(existing.getPayloadHash(), businessHash(envelope));
					if (!existing.getSubmissionId().equals(id) || !existing.getOperation().equals(type)) {
						conflict("IDEMPOTENCY_KEY_REUSED");
					}
					return true;
				});
				if (duplicate) {
					return;
				}
				boolean dispatchExists = p.tx(() -> repository.find(IntegrationDispatch.class, id) != null);
				if (attempt > 0 || !"CANCEL".equals(type) || !dispatchExists) {
					throw failure;
				}
			}
		}
	}

	public ObjectNode commandEnvelope(String type, JsonNode data, Actor origin) {
		ObjectNode envelope = p.json(Map.of("type", type, "data", LenderCommandPayloads.command(type, data)))
				.deepCopy();
		if (origin != null) {
			envelope.set("originActor", p.json(origin));
		}
		return envelope;
	}

	public ObjectNode traced(JsonNode data, String correlation) {
		if (data == null || !data.isObject()) {
			throw new IllegalArgumentException("OBJECT_REQUIRED");
		}
		ObjectNode trusted = (ObjectNode) data.deepCopy();
		// The authenticated HTTP boundary owns the trace header. A public
		// JSON field cannot replace it, and retries keep the stored trace.
		String header = correlation == null ? org.slf4j.MDC.get("correlationId") : correlation;
		trusted.put("correlationId", correlation(header, UUID.randomUUID()));
		return trusted;
	}

	public String correlation(String supplied, UUID fallback) {
		String value = supplied == null ? fallback.toString() : supplied;
		if (!value.matches("[A-Za-z0-9._:-]{1,100}")) {
			throw new IllegalArgumentException("INVALID_CORRELATION_ID");
		}
		return value;
	}

	public String businessHash(JsonNode payload) {
		ObjectNode business = (ObjectNode) payload.deepCopy();
		business.remove(List.of("correlationId", "causationId"));
		if (business.path("data").isObject()) {
			ObjectNode data = (ObjectNode) business.path("data");
			data.remove(List.of("correlationId", "causationId", "decidedAt"));
		}
		return p.hash(business);
	}

	public Event cause(JsonNode payload, UUID fallback) {
		JsonNode data = payload.path("data").isObject() ? payload.path("data") : payload;
		UUID causeId = data.hasNonNull("causationId") ? uuid(data, "causationId") : fallback;
		String trace = correlation(data.hasNonNull("correlationId") ? text(data, "correlationId") : null, fallback);
		return new Event(causeId, "lender.command", "1.0", Instant.now(), "lender-integration-service", null, 1, trace,
				null, p.json(null), p.systemActor(), origin(p.write(payload)));
	}

	public String trace(IntegrationDispatch dispatch) {
		JsonNode request = dispatch.getRequestJson() == null ? p.json(Map.of()) : parse(dispatch.getRequestJson());
		return correlation(request.hasNonNull("correlationId") ? text(request, "correlationId") : null,
				dispatch.getSubmissionId());
	}

	public void enqueue(UUID id, UUID commandId, String type, JsonNode envelope) {
		JsonNode data = envelope.path("data");
		IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, id, LockModeType.PESSIMISTIC_WRITE);
		if (dispatch == null && "CANCEL".equals(type)) {
			dispatch = new IntegrationDispatch();
			dispatch.setSubmissionId(id);
			dispatch.setApplicationId(uuid(data, "applicationId"));
			dispatch.setApplicationRevision(data.path("applicationRevision").asInt());
			dispatch.setLenderId(text(data, "lenderId"));
			callbackClient(dispatch.getLenderId());
			if (dispatch.getApplicationRevision() < 1) {
				throw new IllegalArgumentException("INVALID_REVISION");
			}
			if (data.hasNonNull("submissionId") && !id.equals(uuid(data, "submissionId"))) {
				conflict("SUBMISSION_ID_MISMATCH");
			}
			dispatch.setRequestJson(p.write(data));
			dispatch.setPayloadHash(p.hash(data));
			dispatch.setStatus("CANCELLED");
			repository.save(dispatch);
		}
		if (dispatch == null) {
			throw new NoSuchElementException("SUBMISSION_NOT_FOUND");
		}
		if (dispatch.isCancellationRequested() && !"CANCEL".equals(type)) {
			conflict("SUBMISSION_CANCELLED");
		}
		if ("CANCEL".equals(type)) {
			dispatch.setCancellationRequested(true);
		}
		JsonNode offer = "CREATE_OFFER".equals(type) ? data : data.path("offer");
		if (offer.isObject()) {
			JsonNode original = parse(dispatch.getRequestJson());
			if (new java.math.BigDecimal(text(offer, "offeredAmount"))
					.compareTo(new java.math.BigDecimal(text(original, "requestedAmount"))) > 0) {
				throw new IllegalArgumentException("OFFER_EXCEEDS_REQUEST");
			}
		}
		saveTask(commandId, id, type, envelope);
	}

	public void saveTask(UUID taskId, UUID submission, String operation, JsonNode payload) {
		IntegrationTask existing = repository.find(IntegrationTask.class, taskId);
		if (existing != null) {
			if (!existing.getSubmissionId().equals(submission) || !existing.getOperation().equals(operation)) {
				conflict("IDEMPOTENCY_KEY_REUSED");
			}
			same(existing.getPayloadHash(), businessHash(payload));
			return;
		}
		IntegrationTask task = new IntegrationTask();
		task.setTaskId(taskId);
		task.setSubmissionId(submission);
		task.setOperation(operation);
		task.setPayloadHash(businessHash(payload));
		ObjectNode durable = (ObjectNode) payload.deepCopy();
		JsonNode offer = "CREATE_OFFER".equals(operation) ? durable.path("data") : durable.path("data").path("offer");
		if (offer.isObject() && !Instant.parse(text(offer, "expiresAt")).isAfter(Instant.now())) {
			throw new IllegalArgumentException("OFFER_ALREADY_EXPIRED");
		}
		if ("DECISION".equals(operation)) {
			((ObjectNode) durable.path("data")).put("decidedAt", Instant.now().toString());
		}
		task.setPayloadJson(p.write(durable));
		task.setPosition(((Number) repository.nextDeliveryPositionQuery().getSingleResult()).longValue());
		repository.save(task);
	}

	public void callback(String lender, JsonNode body, Actor actor, String correlation) {
		validateCallback(lender, body);
		UUID eventId = uuid(body, "eventId");
		UUID submissionId = uuid(body, "submissionId");
		String hash = p.hash(body);
		String correlationId = correlation(correlation, submissionId);
		try {
			p.tx(() -> {
				IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, submissionId,
						LockModeType.PESSIMISTIC_WRITE);
				validateCallbackDispatch(dispatch, body);
				trustedOrigin(body, dispatch);
				IntegrationCallback old = repository.find(IntegrationCallback.class, eventId);
				if (old != null) {
					same(old.getPayloadHash(), hash);
					return null;
				}
				List<IntegrationCallback> bySequence = repository.callbackAtSequenceQuery()
						.setParameter("s", submissionId).setParameter("n", body.path("lenderSequence").asLong())
						.getResultList();
				if (!bySequence.isEmpty()) {
					conflict("CALLBACK_SEQUENCE_REUSED");
				}
				IntegrationCallback callback = new IntegrationCallback();
				callback.setEventId(eventId);
				callback.setSubmissionId(submissionId);
				callback.setLenderSequence(body.path("lenderSequence").asLong());
				callback.setPayloadHash(hash);
				callback.setCallbackJson(p.write(body));
				callback.setActorJson(p.write(actor));
				callback.setCorrelationId(correlation == null ? trace(dispatch) : correlationId);
				if (body.hasNonNull("causationId")) {
					IntegrationTask task = repository.find(IntegrationTask.class, uuid(body, "causationId"));
					callback.setCorrelationId(cause(parse(task.getPayloadJson()), task.getTaskId()).correlationId());
				}
				repository.save(callback);
				repository.flush();
				return null;
			});
		} catch (RuntimeException failure) {
			p.tx(() -> {
				IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, submissionId,
						LockModeType.PESSIMISTIC_WRITE);
				validateCallbackDispatch(dispatch, body);
				trustedOrigin(body, dispatch);
				IntegrationCallback old = repository.find(IntegrationCallback.class, eventId);
				if (old == null) {
					Long count = repository.countCallbacksAtSequenceQuery().setParameter("s", submissionId)
							.setParameter("n", body.path("lenderSequence").asLong()).getSingleResult();
					if (count > 0) {
						conflict("CALLBACK_SEQUENCE_REUSED");
					}
					throw failure;
				}
				same(old.getPayloadHash(), hash);
				return null;
			});
		}
	}

	public void work() {
		List<UUID> tasks = p.tx(() -> repository.dueTaskIdsQuery().setParameter("now", Instant.now()).setMaxResults(10)
				.getResultList());
		for (UUID id : tasks) {
			IntegrationTask task = p.tx(() -> {
				IntegrationTask current = repository.find(IntegrationTask.class, id);
				IntegrationDispatch dispatch = required(current.getSubmissionId(), true);
				repository.refresh(current, LockModeType.PESSIMISTIC_WRITE);
				if (!Set.of("PENDING", "WORKING").contains(current.getStatus())
						|| current.getNextAttemptAt().isAfter(Instant.now())
						|| (dispatch.getLeaseUntil() != null && dispatch.getLeaseUntil().isAfter(Instant.now()))) {
					return null;
				}
				if (!"CANCEL".equals(current.getOperation())) {
					Long older = repository.countEarlierTasksQuery().setParameter("s", current.getSubmissionId())
							.setParameter("p", current.getPosition()).getSingleResult();
					if (older > 0) {
						return null;
					}
				}
				current.setStatus("WORKING");
				current.incrementAttempts();
				current.setLeaseToken(UUID.randomUUID());
				current.setNextAttemptAt(Instant.now().plusSeconds(120));
				dispatch.setLeaseToken(current.getLeaseToken());
				dispatch.setLeaseUntil(current.getNextAttemptAt());
				return current;
			});
			if (task == null) {
				continue;
			}
			try {
				if ("SUBMIT".equals(task.getOperation())) {
					sendSubmission(task);
				} else {
					sendCommand(task);
				}
				finish(task, null, false);
			} catch (Exception failure) {
				boolean definitive = failure instanceof HttpClientErrorException http
						&& http.getStatusCode().value() != 408 && http.getStatusCode().value() != 429;
				finish(task, failure, definitive);
			}
		}
		drainCallbacks();
	}

	public void sendSubmission(IntegrationTask task) {
		IntegrationDispatch dispatch = p.tx(() -> required(task.getSubmissionId(), false));
		if (dispatch.getExternalId() != null || "CANCELLED".equals(dispatch.getStatus())) {
			return;
		}
		JsonNode receipt = lookup(dispatch);
		if (receipt == null) {
			JsonNode authorization = p.call("orchestration", HttpMethod.GET,
					orchestrationUrl + "/api/v1/internal/submissions/" + dispatch.getSubmissionId() + "/authorization",
					null, null, trace(dispatch));
			validateAuthorization(dispatch, authorization);
			boolean cancelled = p.tx(() -> required(dispatch.getSubmissionId(), false).isCancellationRequested())
					|| authorization.path("closed").asBoolean(false);
			if (cancelled) {
				cancelByReference(dispatch, task.getTaskId(), null);
				return;
			}
			if (!authorization.path("authorized").asBoolean(false))
				throw new ConsentPaused();
			p.tx(() -> {
				required(dispatch.getSubmissionId(), true).setStatus("SENDING");
				return null;
			});
			receipt = p.call("simulator", HttpMethod.POST, base(dispatch.getLenderId()), wireRequest(dispatch),
					dispatch.getSubmissionId().toString(), trace(dispatch));
		}
		confirmReceipt(dispatch, receipt);
	}

	public void validateAuthorization(IntegrationDispatch dispatch, JsonNode authorization) {
		if (authorization == null || !authorization.isObject() || !authorization.path("authorized").isBoolean()
				|| !dispatch.getApplicationId().toString().equals(authorization.path("applicationId").asText())
				|| !authorization.path("applicationRevision").isIntegralNumber()
				|| dispatch.getApplicationRevision() != authorization.path("applicationRevision").asInt()) {
			throw new IllegalStateException("DISPATCH_AUTHORIZATION_BINDING_MISMATCH");
		}
		if (authorization.path("authorized").asBoolean()) {
			JsonNode original = parse(dispatch.getRequestJson());
			if (!original.path("consentId").asText().equals(authorization.path("consentId").asText())
					|| !authorization.path("consentVersion").isIntegralNumber()
					|| original.path("consentVersion").asLong() != authorization.path("consentVersion").asLong()) {
				throw new IllegalStateException("DISPATCH_CONSENT_CHANGED_RECONCILIATION_REQUIRED");
			}
		}
	}

	public void confirmReceipt(IntegrationDispatch dispatch, JsonNode receipt) {
		String external = text(receipt, !nbfcB.equals(dispatch.getLenderId()) ? "bankApplicationId" : "caseId");
		String reference = text(receipt,
				!nbfcB.equals(dispatch.getLenderId()) ? "submissionReference" : "caseReference");
		if (!reference.equals(dispatch.getSubmissionId().toString())) {
			throw new IllegalStateException("LENDER_RECEIPT_REFERENCE_MISMATCH");
		}
		Instant accepted = Instant
				.parse(text(receipt, !nbfcB.equals(dispatch.getLenderId()) ? "acceptedAt" : "receivedTime"));
		p.tx(() -> {
			IntegrationDispatch locked = required(dispatch.getSubmissionId(), true);
			if (locked.getExternalId() != null && !locked.getExternalId().equals(external)) {
				conflict("LENDER_EXTERNAL_ID_MISMATCH");
			}
			if (locked.getExternalId() == null) {
				locked.setExternalId(external);
				locked.setStatus("SUBMITTED");
				locked.setSubmittedAt(accepted);
				ObjectNode data = metadata(locked);
				data.put("receivedAt", accepted.toString());
				p.emit("lender.application.submitted", locked.getApplicationId(), locked.incrementEventVersion(), data,
						p.systemActor(), origin(locked.getRequestJson()),
						cause(parse(locked.getRequestJson()), locked.getSubmissionId()));
			}
			return null;
		});
	}

	public JsonNode lookup(IntegrationDispatch dispatch) {
		try {
			JsonNode receipt = p.call("simulator", HttpMethod.GET, referencePath(dispatch), null, null,
					trace(dispatch));
			if (receipt == null || !receipt.isObject()) {
				throw new IllegalStateException("LENDER_LOOKUP_RESPONSE_INVALID");
			}
			return receipt;
		} catch (HttpClientErrorException.NotFound absent) {
			return null;
		}
	}

	public ObjectNode wireRequest(IntegrationDispatch dispatch) {
		JsonNode source = parse(dispatch.getRequestJson());
		ObjectNode wire = p.json(Map.of()).deepCopy();
		for (String field : List.of("applicationRevision", "lenderId", "productId", "eligibilityResultId",
				"verificationId", "sellerSubject", "originActor", "preferredTenureMonths", "kycStatus",
				"eligibilitySummary")) {
			if (source.hasNonNull(field))
				wire.set(field, source.get(field));
		}
		for (String field : List.of("correlationId", "causationId")) {
			if (source.hasNonNull(field)) {
				wire.set(field, source.get(field));
			}
		}
		String seller = source.hasNonNull("marketplaceSellerId") ? "marketplaceSellerId" : "sellerId";
		Map<String, String> fields = !nbfcB.equals(dispatch.getLenderId())
				? Map.ofEntries(Map.entry("submissionReference", "submissionId"),
						Map.entry("applicationReference", "applicationId"), Map.entry("sellerReference", seller),
						Map.entry("registeredBusinessName", "registeredBusinessName"),
						Map.entry("requestedFacility", "requestedAmount"), Map.entry("currency", "currency"),
						Map.entry("facilityPurpose", "loanPurpose"),
						Map.entry("financialSnapshotReference", "financialSnapshotId"),
						Map.entry("consentId", "consentId"), Map.entry("consentVersion", "consentVersion"))
				: Map.ofEntries(Map.entry("caseReference", "submissionId"),
						Map.entry("credflowApplicationId", "applicationId"), Map.entry("merchantCode", seller),
						Map.entry("legalName", "registeredBusinessName"),
						Map.entry("requestedLimit", "requestedAmount"), Map.entry("currencyCode", "currency"),
						Map.entry("facilityCode", "loanPurpose"),
						Map.entry("financialDataReference", "financialSnapshotId"),
						Map.entry("consentReference", "consentId"), Map.entry("consentRevision", "consentVersion"));
		fields.forEach((target, field) -> wire.set(target, source.get(field)));
		return wire;
	}

	public void cancelByReference(IntegrationDispatch dispatch, UUID commandId, UUID cancellationId) {
		ObjectNode request = p.json(Map.of()).deepCopy();
		if (cancellationId != null) {
			request.put("cancellationId", cancellationId.toString());
		}
		request.set("originActor", p.json(origin(dispatch.getRequestJson())));
		request.put("correlationId", trace(dispatch));
		request.put("causationId", commandId.toString());
		p.call("simulator", HttpMethod.POST,
				referencePath(dispatch) + (!nbfcB.equals(dispatch.getLenderId()) ? "/cancellation" : "/withdraw"),
				request, commandId.toString(), trace(dispatch));
		JsonNode receipt = lookup(dispatch);
		if (receipt != null) {
			confirmReceipt(dispatch, receipt);
		}
		p.tx(() -> {
			IntegrationDispatch locked = required(dispatch.getSubmissionId(), true);
			if (!locked.isCancellationAcknowledged()) {
				locked.setStatus("CANCELLED");
				locked.setCancellationAcknowledged(true);
				ObjectNode data = metadata(locked);
				if (cancellationId != null) {
					data.put("cancellationId", cancellationId.toString());
				}
				data.put("cancellationByReference", true);
				data.put("cancellationAcknowledgementStatus", "ACKNOWLEDGED");
				p.emit("lender.cancellation.acknowledged", locked.getApplicationId(), locked.incrementEventVersion(),
						data, p.systemActor(), origin(locked.getRequestJson()), cause(request, commandId));
			}
			return null;
		});
	}

	static final class ConsentPaused extends RuntimeException {
	}

	public void sendCommand(IntegrationTask task) {
		IntegrationDispatch dispatch = p.tx(() -> required(task.getSubmissionId(), false));
		JsonNode envelope = parse(task.getPayloadJson());
		if ("CANCEL".equals(task.getOperation()) && dispatch.getExternalId() == null) {
			cancelByReference(dispatch, task.getTaskId(), uuid(envelope.path("data"), "cancellationId"));
			return;
		}
		if (dispatch.getExternalId() == null) {
			throw new IllegalStateException("WAITING_FOR_LENDER_RECEIPT");
		}
		if (dispatch.isCancellationRequested() && !"CANCEL".equals(task.getOperation())) {
			throw new HttpClientErrorException(org.springframework.http.HttpStatus.CONFLICT, "SUBMISSION_CANCELLED");
		}
		if ("ANSWER_INFORMATION".equals(task.getOperation())) {
			JsonNode authorization = p.call("orchestration", HttpMethod.GET,
					orchestrationUrl + "/api/v1/internal/submissions/" + dispatch.getSubmissionId() + "/authorization",
					null, null, trace(dispatch));
			validateAuthorization(dispatch, authorization);
			if (!authorization.path("authorized").asBoolean(false))
				throw new ConsentPaused();
		}
		p.call("simulator", HttpMethod.POST,
				base(dispatch.getLenderId()) + "/" + dispatch.getExternalId() + "/commands/" + task.getTaskId(),
				envelope, task.getTaskId().toString(), cause(envelope, task.getTaskId()).correlationId());
	}

	public void finish(IntegrationTask claimed, Exception failure, boolean definitive) {
		p.tx(() -> {
			IntegrationDispatch dispatch = required(claimed.getSubmissionId(), true);
			IntegrationTask task = repository.find(IntegrationTask.class, claimed.getTaskId(),
					LockModeType.PESSIMISTIC_WRITE);
			if (!Objects.equals(claimed.getLeaseToken(), task.getLeaseToken())) {
				return null;
			}
			if (Objects.equals(dispatch.getLeaseToken(), claimed.getLeaseToken())) {
				dispatch.setLeaseToken(null);
				dispatch.setLeaseUntil(null);
			}
			if (failure instanceof ConsentPaused) {
				task.setStatus("PENDING");
				task.setAttempts(Math.max(0, task.getAttempts() - 1));
				task.setNextAttemptAt(Instant.now().plusSeconds(15));
				return null;
			}
			if (failure == null) {
				boolean recovered = task.getFailureCode() != null;
				task.setStatus("DONE");
				task.setFailureCode(null);
				if (recovered) {
					ObjectNode data = taskMetadata(dispatch, task);
					p.emit("lender.processing.recovered", dispatch.getApplicationId(), dispatch.incrementEventVersion(),
							data, p.systemActor(), origin(task.getPayloadJson()),
							cause(parse(task.getPayloadJson()), task.getTaskId()));
				}
			} else {
				task.setFailureCode(failure.getClass().getSimpleName());
				task.setStatus(definitive || task.getAttempts() >= 12 ? "DEAD" : "PENDING");
				task.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(task.getAttempts(), 8))));
				if ("SUBMIT".equals(task.getOperation()) && dispatch.getExternalId() == null
						&& !"CANCELLED".equals(dispatch.getStatus())) {
					dispatch.setStatus(definitive ? "FAILED" : "OUTCOME_UNKNOWN");
				}
				if ("DEAD".equals(task.getStatus()) || task.getAttempts() == 1) {
					ObjectNode data = taskMetadata(dispatch, task);
					data.put("failureCode", task.getFailureCode());
					data.put("outcomeUnknown", !definitive);
					data.put("processingHealth", !definitive ? "OUTCOME_UNKNOWN" : "MANUAL_INTERVENTION_REQUIRED");
					data.put("detailCode",
							"SUBMIT".equals(task.getOperation()) ? "DISPATCH_FAILED" : "LENDER_COMMAND_FAILED");
					p.emit("lender.processing.failed", dispatch.getApplicationId(), dispatch.incrementEventVersion(),
							data, p.systemActor(), origin(task.getPayloadJson()),
							cause(parse(task.getPayloadJson()), task.getTaskId()));
				}
				if ("DEAD".equals(task.getStatus()) && "ACCEPT_OFFER".equals(task.getOperation())) {
					JsonNode command = parse(task.getPayloadJson()).path("data");
					ObjectNode acknowledgement = metadata(dispatch);
					acknowledgement.put("taskId", task.getTaskId().toString());
					acknowledgement.put("operation", task.getOperation());
					acknowledgement.set("offerId", command.get("offerId"));
					acknowledgement.set("acceptanceId", command.get("acceptanceId"));
					acknowledgement.put("lenderAcknowledgementStatus", "FAILED");
					acknowledgement.put("failureCode", "DELIVERY_RETRIES_EXHAUSTED");
					acknowledgement.put("outcomeUnknown", !definitive);
					p.emit("loan.offer.acknowledged", dispatch.getApplicationId(), dispatch.incrementEventVersion(),
							acknowledgement, p.systemActor(), origin(task.getPayloadJson()),
							cause(parse(task.getPayloadJson()), task.getTaskId()));
				}
			}
			return null;
		});
	}

	public ObjectNode taskMetadata(IntegrationDispatch dispatch, IntegrationTask task) {
		ObjectNode data = metadata(dispatch);
		data.put("taskId", task.getTaskId().toString());
		data.put("operation", task.getOperation());
		JsonNode command = parse(task.getPayloadJson()).path("data");
		for (String key : List.of("decisionId", "informationRequestId", "offerId", "acceptanceId", "cancellationId")) {
			if (command.hasNonNull(key)) {
				data.set(key, command.get(key));
			}
		}
		return data;
	}

	public void drainCallbacks() {
		List<UUID> ids = p.tx(() -> repository.dueCallbackIdsQuery().setParameter("now", Instant.now())
				.setMaxResults(100).getResultList());
		for (UUID id : ids) {
			try {
				p.tx(() -> {
					applyCallback(id);
					return null;
				});
			} catch (Exception failure) {
				p.tx(() -> {
					IntegrationCallback callback = repository.find(IntegrationCallback.class, id,
							LockModeType.PESSIMISTIC_WRITE);
					if (callback != null && "PENDING".equals(callback.getStatus())) {
						markCallbackDead(callback, failure.getClass().getSimpleName());
					}
					return null;
				});
			}
		}
	}

	public void applyCallback(UUID id) {
		IntegrationCallback callback = repository.find(IntegrationCallback.class, id, LockModeType.PESSIMISTIC_WRITE);
		if (!"PENDING".equals(callback.getStatus())) {
			return;
		}
		IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, callback.getSubmissionId(),
				LockModeType.PESSIMISTIC_WRITE);
		if (dispatch == null || dispatch.getExternalId() == null
				|| callback.getLenderSequence() != dispatch.getCallbackSequence() + 1) {
			callback.incrementAttempts();
			if (callback.getReceivedAt().isBefore(Instant.now().minusSeconds(86400))
					|| callback.getAttempts() >= 1500) {
				markCallbackDead(callback, "CALLBACK_PREREQUISITE_MISSING");
			} else {
				callback.setNextAttemptAt(
						Instant.now().plusSeconds(Math.min(60, 1L << Math.min(callback.getAttempts(), 6))));
			}
			return;
		}
		JsonNode body = parse(callback.getCallbackJson());
		validateCallbackDispatch(dispatch, body);
		ObjectNode data = LenderCommandPayloads.event(text(body, "eventType"), body.path("data"));
		data.setAll(metadata(dispatch));
		String topic = text(body, "eventType");
		Actor actor = p.read(parse(callback.getActorJson()), Actor.class);
		Actor origin = trustedOrigin(body, dispatch);
		Event cause = new Event(callback.getEventId(), topic, "1.0", Instant.parse(text(body, "occurredAt")),
				"lender-simulator", dispatch.getApplicationId(), callback.getLenderSequence(),
				callback.getCorrelationId(), null, body.path("data"), actor, origin);
		p.emit(topic, dispatch.getApplicationId(), dispatch.incrementEventVersion(), data, actor, origin, cause);
		dispatch.setCallbackSequence(callback.getLenderSequence());
		callback.setStatus("APPLIED");
		if (callback.getFailureCode() != null) {
			ObjectNode recovered = metadata(dispatch);
			recovered.put("taskId", callback.getEventId().toString());
			recovered.put("callbackEventId", callback.getEventId().toString());
			recovered.put("operation", "CALLBACK");
			p.emit("lender.processing.recovered", dispatch.getApplicationId(), dispatch.incrementEventVersion(),
					recovered, actor, origin, cause);
			callback.setFailureCode(null);
		}
		if ("lender.cancellation.acknowledged".equals(topic)) {
			dispatch.setStatus("CANCELLED");
			dispatch.setCancellationAcknowledged(true);
		}
	}

	public void validateCallbackDispatch(IntegrationDispatch dispatch, JsonNode body) {
		// The lender retries a 409 with the same event ID. A callback must not
		// occupy another lender's sequence while its binding is unverified.
		if (dispatch == null) {
			conflict("SUBMISSION_NOT_RESERVED_RETRY");
		}
		if (!dispatch.getApplicationId().equals(uuid(body, "applicationId"))
				|| dispatch.getApplicationRevision() != body.path("applicationRevision").asInt()
				|| !dispatch.getLenderId().equals(text(body, "lenderId")) || (dispatch.getExternalId() != null
						&& !dispatch.getExternalId().equals(text(body, "lenderApplicationId")))) {
			conflict("CALLBACK_DISPATCH_MISMATCH");
		}
	}

	public void markCallbackDead(IntegrationCallback callback, String code) {
		callback.setStatus("DEAD");
		callback.setFailureCode(code);
		IntegrationDispatch dispatch = repository.find(IntegrationDispatch.class, callback.getSubmissionId(),
				LockModeType.PESSIMISTIC_WRITE);
		if (dispatch == null) {
			return;
		}
		try {
			validateCallbackDispatch(dispatch, parse(callback.getCallbackJson()));
		} catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException invalid) {
			return;
		}
		ObjectNode data = metadata(dispatch);
		data.put("operation", "CALLBACK");
		data.put("taskId", callback.getEventId().toString());
		data.put("callbackEventId", callback.getEventId().toString());
		data.put("failureCode", code);
		data.put("outcomeUnknown", false);
		data.put("processingHealth", "MANUAL_INTERVENTION_REQUIRED");
		p.emit("lender.processing.failed", dispatch.getApplicationId(), dispatch.incrementEventVersion(), data,
				p.systemActor(), origin(dispatch.getRequestJson()), callbackCause(callback, dispatch));
	}

	public void replay(UUID id) {
		p.tx(() -> {
			IntegrationTask task = repository.find(IntegrationTask.class, id, LockModeType.PESSIMISTIC_WRITE);
			if (task != null) {
				if (!"DEAD".equals(task.getStatus())) {
					conflict("TASK_NOT_DEAD");
				}
				task.setStatus("PENDING");
				task.setAttempts(0);
				task.setNextAttemptAt(Instant.now());
				return null;
			}
			IntegrationCallback callback = repository.find(IntegrationCallback.class, id,
					LockModeType.PESSIMISTIC_WRITE);
			if (callback == null) {
				throw new NoSuchElementException("TASK_NOT_FOUND");
			}
			if (!"DEAD".equals(callback.getStatus())) {
				conflict("CALLBACK_NOT_DEAD");
			}
			callback.setStatus("PENDING");
			callback.setReceivedAt(Instant.now());
			callback.setNextAttemptAt(Instant.now());
			callback.setAttempts(0);
			// Retain the failure marker until the callback is applied, so a
			// successful replay emits a recovery for this exact callback.
			return null;
		});
	}

	public Event callbackCause(IntegrationCallback callback, IntegrationDispatch dispatch) {
		return new Event(callback.getEventId(), "lender.callback", "1.0", callback.getReceivedAt(), "lender-simulator",
				dispatch.getApplicationId(), Math.max(1, callback.getLenderSequence()),
				callback.getCorrelationId() == null ? trace(dispatch) : callback.getCorrelationId(), null,
				parse(callback.getCallbackJson()), p.systemActor(), origin(dispatch.getRequestJson()));
	}

	public IntegrationDispatch required(UUID id, boolean lock) {
		IntegrationDispatch dispatch = lock
				? repository.find(IntegrationDispatch.class, id, LockModeType.PESSIMISTIC_WRITE)
				: repository.find(IntegrationDispatch.class, id);
		if (dispatch == null) {
			throw new NoSuchElementException("SUBMISSION_NOT_FOUND");
		}
		return dispatch;
	}

	public ObjectNode metadata(IntegrationDispatch dispatch) {
		ObjectNode data = p.json(Map.of("submissionId", dispatch.getSubmissionId(), "applicationRevision",
				dispatch.getApplicationRevision(), "lenderId", dispatch.getLenderId())).deepCopy();
		if (dispatch.getExternalId() != null) {
			data.put("lenderApplicationId", dispatch.getExternalId());
		}
		return data;
	}

	public JsonNode view(IntegrationDispatch dispatch) {
		ObjectNode result = metadata(dispatch);
		result.put("status", dispatch.getStatus());
		if (dispatch.getSubmittedAt() != null) {
			result.put("receivedAt", dispatch.getSubmittedAt().toString());
		}
		return result;
	}

	public JsonNode parse(String json) {
		return p.read(json, JsonNode.class);
	}

	public Actor origin(String json) {
		JsonNode node = parse(json).path("originActor");
		return node.isMissingNode() || node.isNull() ? null : p.read(node, Actor.class);
	}

	public Actor trustedOrigin(JsonNode body, IntegrationDispatch dispatch) {
		Actor initial = origin(dispatch.getRequestJson());
		Actor trusted = initial;
		if (body.hasNonNull("causationId")) {
			IntegrationTask task = repository.find(IntegrationTask.class, uuid(body, "causationId"));
			if (task == null || !dispatch.getSubmissionId().equals(task.getSubmissionId())) {
				throw new IllegalArgumentException("CALLBACK_COMMAND_BINDING_MISMATCH");
			}
			String topic = text(body, "eventType");
			Set<String> expectedTopics = switch (task.getOperation()) {
			case "DECISION" -> Set.of("lender.decision.received", "lender.information.requested", "loan.offer.created");
			case "REQUEST_INFORMATION" -> Set.of("lender.information.requested");
			case "ANSWER_INFORMATION" -> Set.of("lender.information.answered");
			case "CREATE_OFFER" -> Set.of("loan.offer.created");
			case "WITHDRAW_OFFER" -> Set.of("loan.offer.withdrawn");
			case "ACCEPT_OFFER" -> Set.of("loan.offer.acknowledged");
			case "CANCEL", "SUBMIT" -> Set.of("lender.cancellation.acknowledged");
			default -> Set.of();
			};
			if (!expectedTopics.contains(topic)) {
				throw new IllegalArgumentException("CALLBACK_COMMAND_OPERATION_MISMATCH");
			}
			JsonNode command = parse(task.getPayloadJson()).path("data");
			if ("DECISION".equals(task.getOperation())) {
				String decision = text(command, "decision");
				if (("lender.information.requested".equals(topic)
						&& !Set.of("ACTION_REQUIRED", "MANUAL_REVIEW").contains(decision))
						|| ("loan.offer.created".equals(topic)
								&& (!"APPROVED".equals(decision) || !command.path("offer").isObject()))) {
					throw new IllegalArgumentException("CALLBACK_DECISION_OPERATION_MISMATCH");
				}
				if ("lender.decision.received".equals(topic) && (!decision.equals(text(body.path("data"), "decision"))
						|| !Instant.parse(text(command, "decidedAt"))
								.equals(Instant.parse(text(body.path("data"), "decidedAt"))))) {
					throw new IllegalArgumentException("CALLBACK_DECISION_MISMATCH");
				}
			}
			if ("DECISION".equals(task.getOperation()) && "loan.offer.created".equals(topic)) {
				command = command.path("offer");
			}
			JsonNode result = body.path("data");
			if ("loan.offer.created".equals(topic)) {
				for (String key : List.of("currency", "interestRateType", "tenureMonths", "repaymentFrequency",
						"termsReference")) {
					if (!command.path(key).equals(result.path(key))) {
						throw new IllegalArgumentException("CALLBACK_OFFER_TERMS_MISMATCH");
					}
				}
				for (String key : List.of("offeredAmount", "feesAmount", "annualInterestRatePercent")) {
					if (new java.math.BigDecimal(text(command, key))
							.compareTo(new java.math.BigDecimal(text(result, key))) != 0) {
						throw new IllegalArgumentException("CALLBACK_OFFER_TERMS_MISMATCH");
					}
				}
				if (!Instant.parse(text(command, "expiresAt")).equals(Instant.parse(text(result, "expiresAt")))) {
					throw new IllegalArgumentException("CALLBACK_OFFER_TERMS_MISMATCH");
				}
			}
			if ("lender.information.requested".equals(topic)
					&& !command.path("questions").equals(result.path("questions"))) {
				throw new IllegalArgumentException("CALLBACK_QUESTIONS_MISMATCH");
			}
			if ("lender.information.answered".equals(topic)
					&& !command.path("answers").equals(result.path("answers"))) {
				throw new IllegalArgumentException("CALLBACK_ANSWERS_MISMATCH");
			}
			for (String key : List.of("decisionId", "informationRequestId", "offerId", "acceptanceId",
					"cancellationId")) {
				if (result.hasNonNull(key) && command.hasNonNull(key) && !result.path(key).equals(command.path(key))) {
					throw new IllegalArgumentException("CALLBACK_COMMAND_RESOURCE_MISMATCH");
				}
			}
			trusted = origin(task.getPayloadJson());
		}
		if (body.hasNonNull("originActor") && !Objects.equals(trusted, p.read(body.get("originActor"), Actor.class))) {
			throw new IllegalArgumentException("CALLBACK_ORIGIN_ACTOR_MISMATCH");
		}
		return trusted;
	}

	public void validateSubmission(JsonNode data) {
		for (String field : List.of("submissionId", "applicationId", "productId", "financialSnapshotId", "consentId",
				"eligibilityResultId", "verificationId")) {
			uuid(data, field);
		}
		callbackClient(text(data, "lenderId"));
		// Member 1's trusted seller IDs are opaque strings, independent of JWT sub.
		if (data.hasNonNull("sellerSubject"))
			text(data, "sellerSubject");
		if (data.hasNonNull("sellerId")) {
			if (text(data, "sellerId").length() > 120) {
				throw new IllegalArgumentException("INVALID_SELLER_ID");
			}
		} else if (!data.hasNonNull("sellerSubject")) {
			throw new IllegalArgumentException("TRUSTED_SELLER_MAPPING_REQUIRED");
		}
		if (text(data, "registeredBusinessName").length() > 255) {
			throw new IllegalArgumentException("BUSINESS_NAME_TOO_LONG");
		}
		if (!Set.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION")
				.contains(text(data, "loanPurpose"))) {
			throw new IllegalArgumentException("INVALID_LOAN_PURPOSE");
		}
		if (!data.hasNonNull("marketplaceSellerId") && !data.hasNonNull("sellerId")) {
			throw new IllegalArgumentException("MISSING_MARKETPLACE_SELLER_ID");
		}
		text(data, data.hasNonNull("marketplaceSellerId") ? "marketplaceSellerId" : "sellerId");
		if (data.hasNonNull("causationId")) {
			uuid(data, "causationId");
		}
		money(data, "requestedAmount", true);
		if (!"INR".equals(text(data, "currency")) || !positiveInteger(data, "applicationRevision")
				|| !positiveInteger(data, "consentVersion") || !data.hasNonNull("originActor")) {
			throw new IllegalArgumentException("INVALID_SUBMISSION");
		}
	}

	public void validateCommand(String type, JsonNode data) {
		switch (type) {
		case "DECISION" -> {
			uuid(data, "decisionId");
			if (!Set.of("APPROVED", "REJECTED", "MANUAL_REVIEW", "ACTION_REQUIRED").contains(text(data, "decision"))) {
				throw new IllegalArgumentException("INVALID_DECISION");
			}
			if ("REJECTED".equals(text(data, "decision"))) {
				if (!text(data, "reasonCode").matches("[A-Z][A-Z0-9_]{0,99}"))
					throw new IllegalArgumentException("INVALID_REJECTION_REASON_CODE");
			}
			if (Set.of("ACTION_REQUIRED", "MANUAL_REVIEW").contains(text(data, "decision"))) {
				validateCommand("REQUEST_INFORMATION", data);
			}
			if (data.has("offer")) {
				if (!"APPROVED".equals(text(data, "decision")) || !data.path("offer").isObject()) {
					throw new IllegalArgumentException("INVALID_DECISION_OFFER");
				}
				validateOffer(data.path("offer"));
			}
		}
		case "REQUEST_INFORMATION" -> {
			uuid(data, "informationRequestId");
			if (!data.path("questions").isArray() || data.path("questions").isEmpty()
					|| data.path("questions").size() > 50) {
				throw new IllegalArgumentException("QUESTIONS_REQUIRED");
			}
			Set<String> ids = new HashSet<>();
			for (JsonNode question : data.path("questions")) {
				if (!ids.add(text(question, "questionId")) || text(question, "prompt").length() > 1000) {
					throw new IllegalArgumentException("INVALID_QUESTIONS");
				}
			}
		}
		case "ANSWER_INFORMATION" -> {
			uuid(data, "informationRequestId");
			if (data.has("syntheticDocumentReferences")) {
				if (!data.path("syntheticDocumentReferences").isArray()) {
					throw new IllegalArgumentException("INVALID_SYNTHETIC_REFERENCES");
				}
				for (JsonNode ref : data.path("syntheticDocumentReferences")) {
					if (!ref.isTextual() || !ref.asText().matches("synthetic://[A-Za-z0-9_./-]{1,200}")) {
						throw new IllegalArgumentException("INVALID_SYNTHETIC_REFERENCES");
					}
				}
			}
			if (!data.path("answers").isArray() || data.path("answers").isEmpty() || data.path("answers").size() > 50) {
				throw new IllegalArgumentException("ANSWERS_REQUIRED");
			}
			Set<String> ids = new HashSet<>();
			for (JsonNode answer : data.path("answers")) {
				if (answer.has("answer") || answer.has("documentReferences")) {
					throw new IllegalArgumentException("USE_ANSWER_TEXT_AND_SYNTHETIC_REFERENCES");
				}
				String value = text(answer, "answerText");
				if (!ids.add(text(answer, "questionId")) || value.length() > 4000) {
					throw new IllegalArgumentException("INVALID_ANSWERS");
				}
			}
		}
		case "CREATE_OFFER" -> validateOffer(data);
		case "WITHDRAW_OFFER", "DECLINE_OFFER" -> uuid(data, "offerId");
		case "ACCEPT_OFFER" -> {
			uuid(data, "offerId");
			uuid(data, "acceptanceId");
			if (!data.path("expectedOfferVersion").isIntegralNumber()
					|| !data.path("expectedOfferVersion").canConvertToLong()
					|| data.path("expectedOfferVersion").asLong() < 0) {
				throw new IllegalArgumentException("INVALID_OFFER_VERSION");
			}
			Instant accepted = Instant.parse(text(data, "acceptedAt"));
			if (accepted.isAfter(Instant.now().plusSeconds(60))) {
				throw new IllegalArgumentException("ACCEPTANCE_TIME_IN_FUTURE");
			}
		}
		case "CANCEL" -> uuid(data, "cancellationId");
		default -> throw new IllegalArgumentException("INVALID_COMMAND");
		}
	}

	public void validateCallback(String lender, JsonNode data) {
		uuid(data, "eventId");
		uuid(data, "applicationId");
		uuid(data, "submissionId");
		if (data.hasNonNull("causationId"))
			uuid(data, "causationId");
		if (!lender.equals(text(data, "lenderId")) || !positiveInteger(data, "lenderSequence")
				|| !positiveInteger(data, "applicationRevision") || !data.path("data").isObject()) {
			throw new IllegalArgumentException("INVALID_CALLBACK");
		}
		text(data, "lenderApplicationId");
		if (Instant.parse(text(data, "occurredAt")).isAfter(Instant.now().plusSeconds(300))) {
			throw new IllegalArgumentException("CALLBACK_TIME_IN_FUTURE");
		}
		String topic = text(data, "eventType");
		if (!TYPES.contains(topic)) {
			throw new IllegalArgumentException("INVALID_CALLBACK_TYPE");
		}
		JsonNode payload = data.path("data");
		validateOptionalCallbackFields(payload);
		// Reject unsupported nested answer contracts before they can occupy a
		// callback sequence. Unknown extension fields are discarded on emit.
		LenderCommandPayloads.event(topic, payload);
		switch (topic) {
		case "lender.decision.received" -> {
			validateCommand("DECISION", payload);
			Instant decided = Instant.parse(text(payload, "decidedAt"));
			if (decided.isAfter(Instant.now().plusSeconds(60))) {
				throw new IllegalArgumentException("DECISION_TIME_IN_FUTURE");
			}
		}
		case "lender.information.requested" -> validateCommand("REQUEST_INFORMATION", payload);
		case "loan.offer.created" -> validateOffer(payload);
		case "lender.information.answered" -> uuid(payload, "informationRequestId");
		case "loan.offer.acknowledged" -> {
			uuid(payload, "offerId");
			uuid(payload, "acceptanceId");
			if (!Set.of("ACKNOWLEDGED", "FAILED").contains(text(payload, "lenderAcknowledgementStatus"))) {
				throw new IllegalArgumentException("INVALID_ACCEPTANCE_ACK");
			}
		}
		case "loan.offer.withdrawn" -> uuid(payload, "offerId");
		case "lender.cancellation.acknowledged" -> {
			if (!"ACKNOWLEDGED".equals(text(payload, "cancellationAcknowledgementStatus"))) {
				throw new IllegalArgumentException("INVALID_CANCELLATION_ACK");
			}
		}
		default -> throw new IllegalArgumentException("INVALID_CALLBACK_TYPE");
		}
	}

	public void validateOptionalCallbackFields(JsonNode payload) {
		if (payload.has("answers"))
			validateCommand("ANSWER_INFORMATION", payload);
		if (payload.has("questions"))
			validateCommand("REQUEST_INFORMATION", payload);
		if (payload.has("syntheticDocumentReferences")) {
			JsonNode references = payload.path("syntheticDocumentReferences");
			if (!references.isArray()) {
				throw new IllegalArgumentException("INVALID_SYNTHETIC_REFERENCES");
			}
			for (JsonNode reference : references) {
				if (!reference.isTextual() || !reference.asText().matches("synthetic://[A-Za-z0-9_./-]{1,200}")) {
					throw new IllegalArgumentException("INVALID_SYNTHETIC_REFERENCES");
				}
			}
		}
		for (String key : List.of("decisionId", "informationRequestId", "offerId", "acceptanceId", "cancellationId",
				"taskId")) {
			if (payload.has(key))
				uuid(payload, key);
		}
		if (payload.has("decision") && !Set.of("APPROVED", "REJECTED", "ACTION_REQUIRED", "MANUAL_REVIEW")
				.contains(text(payload, "decision"))) {
			throw new IllegalArgumentException("INVALID_DECISION");
		}
		for (String key : List.of("decidedAt", "acceptedAt", "operationAt", "createdAt", "answeredAt")) {
			if (!payload.has(key))
				continue;
			Instant timestamp = Instant.parse(text(payload, key));
			long allowance = key.equals("decidedAt") || key.equals("acceptedAt") ? 60 : 300;
			if (timestamp.isAfter(Instant.now().plusSeconds(allowance))) {
				throw new IllegalArgumentException("CALLBACK_TIMESTAMP_IN_FUTURE");
			}
		}
		if (payload.has("expectedOfferVersion") && (!payload.path("expectedOfferVersion").isIntegralNumber()
				|| !payload.path("expectedOfferVersion").canConvertToLong()
				|| payload.path("expectedOfferVersion").asLong() < 0)) {
			throw new IllegalArgumentException("INVALID_OFFER_VERSION");
		}
		for (String key : List.of("reasonCode", "reasonText", "reason", "failureCode")) {
			if (payload.has(key))
				text(payload, key);
		}
		for (String key : List.of("outcomeUnknown", "cancellationByReference")) {
			if (payload.has(key) && !payload.path(key).isBoolean()) {
				throw new IllegalArgumentException("INVALID_CALLBACK_BOOLEAN");
			}
		}
		if (payload.has("lenderAcknowledgementStatus")
				&& !Set.of("ACKNOWLEDGED", "FAILED").contains(text(payload, "lenderAcknowledgementStatus"))) {
			throw new IllegalArgumentException("INVALID_ACCEPTANCE_ACK");
		}
		if (payload.has("operation") && !Set.of("SUBMIT", "DECISION", "REQUEST_INFORMATION", "ANSWER_INFORMATION",
				"CREATE_OFFER", "WITHDRAW_OFFER", "ACCEPT_OFFER", "DECLINE_OFFER", "CANCEL", "CALLBACK")
				.contains(text(payload, "operation"))) {
			throw new IllegalArgumentException("INVALID_COMMAND");
		}
	}

	public void validateOffer(JsonNode data) {
		uuid(data, "offerId");
		money(data, "offeredAmount", true);
		money(data, "feesAmount", false);
		String rate = text(data, "annualInterestRatePercent");
		if (!rate.matches("[0-9]{1,3}(\\.[0-9]{1,4})?")
				|| new java.math.BigDecimal(rate).compareTo(new java.math.BigDecimal("100")) > 0
				|| !"INR".equals(text(data, "currency")) || !"FIXED".equals(text(data, "interestRateType"))
				|| !"MONTHLY".equals(text(data, "repaymentFrequency")) || !positiveInteger(data, "tenureMonths")
				|| data.path("tenureMonths").asInt() > 120 || (data.has("version")
						&& (!data.path("version").isIntegralNumber() || data.path("version").asLong() != 0))) {
			throw new IllegalArgumentException("INVALID_OFFER_TERMS");
		}
		if (text(data, "termsReference").length() > 255) {
			throw new IllegalArgumentException("TERMS_REFERENCE_TOO_LONG");
		}
		Instant.parse(text(data, "expiresAt"));
	}

	static public void money(JsonNode data, String key, boolean positive) {
		String value = text(data, key);
		if (!data.path(key).isTextual() || !value.matches("[0-9]{1,17}\\.[0-9]{2}")
				|| (positive && new java.math.BigDecimal(value).signum() <= 0)) {
			throw new IllegalArgumentException("INVALID_" + key.toUpperCase(Locale.ROOT));
		}
	}

	static public String text(JsonNode data, String key) {
		JsonNode value = data.path(key);
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new IllegalArgumentException("MISSING_" + key.toUpperCase(Locale.ROOT));
		}
		return value.asText();
	}

	static public UUID uuid(JsonNode data, String key) {
		String value = text(data, key);
		if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("INVALID_" + key.toUpperCase(Locale.ROOT));
		}
		return UUID.fromString(value);
	}

	static public boolean positiveInteger(JsonNode data, String key) {
		JsonNode value = data.path(key);
		return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0;
	}

	static public void same(String first, String second) {
		if (!Objects.equals(first, second)) {
			conflict("PAYLOAD_CONFLICT");
		}
	}

	static public void conflict(String message) {
		throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
				message);
	}
}
