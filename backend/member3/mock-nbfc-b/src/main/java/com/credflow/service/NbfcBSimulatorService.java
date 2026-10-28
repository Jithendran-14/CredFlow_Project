package com.credflow.service;

import com.credflow.dto.Delivery;
import com.credflow.dto.NbfcBReceipt;
import com.credflow.entity.SimCallback;
import com.credflow.entity.SimCase;
import com.credflow.platform.LenderCommandPayloads;
import com.credflow.platform.Platform;
import com.credflow.repository.NbfcBSimulatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.*;
import java.math.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class NbfcBSimulatorService {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NbfcBSimulatorService.class);

	private final NbfcBSimulatorRepository repository;

	private final Platform p;

	private final String integrationClient;

	private final String bankCode;

	private final String lenderId;

	private final String callbackBase;

	public NbfcBSimulatorService(NbfcBSimulatorRepository repository, Platform p,
			@Value("${credflow.simulator.integration-client-id:integration-service}") String integrationClient,
			@Value("${credflow.simulator.bank-code}") String bankCode,
			@Value("${credflow.simulator.lender-id}") String lenderId,
			@Value("${credflow.simulator.callback-base:http://localhost:8087}") String callbackBase) {
		this.repository = repository;
		this.p = p;
		this.integrationClient = integrationClient;
		if (!"nbfc-b".equals(bankCode)) {
			throw new IllegalArgumentException("INVALID_SIMULATOR_BANK_CODE");
		}
		UUID.fromString(lenderId);
		this.bankCode = bankCode;
		this.lenderId = lenderId;
		this.callbackBase = callbackBase;
	}

	private void authorize(Jwt jwt, String scope) {
		p.machine(jwt, scope, integrationClient);
	}

	private void requireBank(String bank) {
		if (!bankCode.equals(bank)) {
			throw failure(HttpStatus.NOT_FOUND, "LENDER_NOT_FOUND");
		}
	}

	private String lender(String bank) {
		requireBank(bank);
		return lenderId;
	}

	private String key(String bank, UUID reference) {
		lender(bank);
		return bank + ":" + reference;
	}

	private ResponseStatusException failure(HttpStatus status, String reason) {
		return new ResponseStatusException(status, reason);
	}

	private void require(boolean condition, String reason) {
		if (!condition) {
			throw failure(HttpStatus.CONFLICT, reason);
		}
	}

	private String text(JsonNode node, String name) {
		JsonNode value = node.get(name);
		if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
			throw failure(HttpStatus.BAD_REQUEST, "MISSING_" + name);
		}
		return value.asText();
	}

	private UUID uuid(JsonNode node, String name) {
		try {
			String value = text(node, name);
			if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
				throw new IllegalArgumentException();
			}
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_" + name);
		}
	}

	private BigDecimal decimal(JsonNode node, String name, boolean positive, int scale) {
		try {
			String input = text(node, name);
			String pattern = scale == 2 ? "[0-9]{1,17}\\.[0-9]{2}" : "[0-9]{1,3}(\\.[0-9]{1,4})?";
			if (!input.matches(pattern)) {
				throw new ArithmeticException();
			}
			BigDecimal value = new BigDecimal(input).setScale(scale, RoundingMode.UNNECESSARY);
			if (value.signum() < 0 || (positive && value.signum() == 0) || value.precision() > 19) {
				throw new ArithmeticException();
			}
			return value;
		} catch (ArithmeticException | NumberFormatException exception) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_" + name);
		}
	}

	private Instant instant(JsonNode node, String field) {
		try {
			return Instant.parse(text(node, field));
		} catch (java.time.format.DateTimeParseException exception) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_" + field);
		}
	}

	private ObjectNode readState(SimCase application) {
		return application.getStateJson() == null ? JsonNodeFactory.instance.objectNode()
				: parse(application.getStateJson());
	}

	private ObjectNode parse(String json) {
		try {
			return (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		} catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private ObjectNode normalize(JsonNode wire) {
		if (!wire.isObject()) {
			throw failure(HttpStatus.BAD_REQUEST, "OBJECT_REQUIRED");
		}
		ObjectNode normalized = (ObjectNode) wire.deepCopy();
		String[][] aliases = { { "submissionId", "submissionReference", "caseReference" },
				{ "applicationId", "applicationReference", "credflowApplicationId" },
				{ "marketplaceSellerId", "sellerReference", "merchantCode" },
				{ "registeredBusinessName", "registeredBusinessName", "legalName" },
				{ "requestedAmount", "requestedFacility", "requestedLimit" },
				{ "currency", "currency", "currencyCode" }, { "loanPurpose", "facilityPurpose", "facilityCode" },
				{ "financialSnapshotId", "financialSnapshotReference", "financialDataReference" },
				{ "consentId", "consentId", "consentReference" },
				{ "consentVersion", "consentVersion", "consentRevision" } };
		for (String[] alias : aliases) {
			if (!normalized.hasNonNull(alias[0])) {
				for (int index = 1; index < alias.length; index++) {
					if (wire.hasNonNull(alias[index])) {
						normalized.set(alias[0], wire.get(alias[index]));
						break;
					}
				}
			}
		}
		return normalized;
	}

	private String trace(String supplied, UUID fallback) {
		String header = supplied == null ? org.slf4j.MDC.get("correlationId") : supplied;
		String value = header == null ? fallback.toString() : header;
		if (!value.matches("[A-Za-z0-9._:-]{1,100}")) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_CORRELATION_ID");
		}
		return value;
	}

	private String storedTrace(SimCase application) {
		JsonNode request = readState(application).path("request");
		return trace(request.hasNonNull("correlationId") ? text(request, "correlationId") : null,
				application.getSubmissionId());
	}

	private String businessHash(JsonNode body) {
		ObjectNode business = (ObjectNode) body.deepCopy();
		business.remove(List.of("correlationId", "causationId"));
		if (business.path("data").isObject()) {
			((ObjectNode) business.path("data")).remove(List.of("correlationId", "causationId"));
		}
		return p.hash(business);
	}

	private void save(SimCase application, ObjectNode state) {
		application.setStateJson(p.write(state));
	}

	private ObjectNode map(ObjectNode parent, String name) {
		return parent.withObject("/" + name);
	}

	private <T> T atomic(Supplier<T> work) {
		for (int attempt = 0;; attempt++) {
			try {
				return p.tx(work);
			} catch (RuntimeException exception) {
				boolean duplicate = false;
				for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
					if (cause instanceof SQLException sql && sql.getErrorCode() == 1) {
						duplicate = true;
					}
				}
				if (!duplicate || attempt >= 2) {
					throw exception;
				}
			}
		}
	}

	private SimCase locked(String bank, UUID reference) {
		return repository.find(SimCase.class, key(bank, reference), LockModeType.PESSIMISTIC_WRITE);
	}

	private SimCase external(String bank, String externalId) {
		List<SimCase> rows = repository.caseByExternalIdQuery().setParameter("b", bank).setParameter("x", externalId)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
		if (rows.isEmpty()) {
			throw failure(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND");
		}
		return rows.getFirst();
	}

	private Object receipt(SimCase application) {
		return new NbfcBReceipt(application.getExternalId(), application.getSubmissionId().toString(), "RECEIVED",
				application.getAcceptedAt());
	}

	public Object submit(String bank, JsonNode wire, Jwt jwt, String correlation) {
		requireBank(bank);
		authorize(jwt, "lender.submit");
		ObjectNode request = normalize(wire);
		UUID reference = uuid(request, "submissionId");
		UUID applicationId = uuid(request, "applicationId");
		require(lender(bank).equals(text(request, "lenderId")), "LENDER_MISMATCH");
		int revision = request.path("applicationRevision").asInt(0);
		if (!request.path("applicationRevision").isIntegralNumber()
				|| !request.path("applicationRevision").canConvertToInt() || revision < 1) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_REVISION");
		}
		BigDecimal amount = decimal(request, "requestedAmount", true, 2);
		require("INR".equals(text(request, "currency")), "UNSUPPORTED_CURRENCY");
		for (String field : List.of("productId", "financialSnapshotId", "consentId", "eligibilityResultId",
				"verificationId")) {
			uuid(request, field);
		}
		text(request, "marketplaceSellerId");
		if (text(request, "registeredBusinessName").length() > 255) {
			throw failure(HttpStatus.BAD_REQUEST, "BUSINESS_NAME_TOO_LONG");
		}
		if (!Set.of("PURCHASE_INVENTORY", "WORKING_CAPITAL", "BUSINESS_EXPANSION")
				.contains(text(request, "loanPurpose"))) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_LOAN_PURPOSE");
		}
		if (request.hasNonNull("sellerSubject"))
			text(request, "sellerSubject");
		if (!request.path("consentVersion").isIntegralNumber() || request.path("consentVersion").asLong() < 1
				|| !request.path("originActor").isObject()) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION");
		}
		String hash = businessHash(wire);
		request.put("correlationId", trace(correlation, reference));
		return atomic(() -> {
			SimCase application = locked(bank, reference);
			if (application != null) {
				require(application.getPayloadHash() != null, "SIMULATOR_CANCELLED");
				require(application.getPayloadHash().equals(hash), "REFERENCE_PAYLOAD_CONFLICT");
				return receipt(application);
			}
			application = new SimCase();
			application.setId(key(bank, reference));
			application.setLenderCode(bank);
			application.setLenderId(lender(bank));
			application.setSubmissionId(reference);
			application.setApplicationId(applicationId);
			application.setRevision(revision);
			application.setExternalId((bank.equals("bank-a") ? "BA-" : "NB-") + reference);
			application.setPayloadHash(hash);
			application.setStatus("UNDER_REVIEW");
			application.setAcceptedAt(Instant.now());
			application.setRequestedAmount(amount);
			ObjectNode state = JsonNodeFactory.instance.objectNode();
			state.set("request", request.deepCopy());
			save(application, state);
			repository.save(application);
			repository.flush();
			return receipt(application);
		});
	}

	public Object lookup(String bank, UUID reference, Jwt jwt) {
		requireBank(bank);
		authorize(jwt, "lender.lookup");
		return p.tx(() -> {
			SimCase application = locked(bank, reference);
			if (application == null || application.getPayloadHash() == null) {
				throw failure(HttpStatus.NOT_FOUND, "REFERENCE_NOT_FOUND");
			}
			return receipt(application);
		});
	}

	public Map<String, Object> cancelReference(String bank, UUID reference, JsonNode body, Jwt jwt,
			String correlation) {
		requireBank(bank);
		authorize(jwt, "lender.command");
		return atomic(() -> {
			SimCase application = locked(bank, reference);
			if (application == null) {
				application = new SimCase();
				application.setId(key(bank, reference));
				application.setLenderCode(bank);
				application.setLenderId(lender(bank));
				application.setSubmissionId(reference);
				application.setStatus("CANCELLED");
				repository.save(application);
			} else if (!"CANCELLED".equals(application.getStatus())) {
				application.setCommandCorrelationId(trace(correlation, reference));
				application.setCommandCausationId(
						body != null && body.hasNonNull("causationId") ? uuid(body, "causationId") : reference);
				require(!Set.of("OFFER_ACCEPTED", "OFFER_DECLINED", "REJECTED").contains(application.getStatus()),
						"TERMINAL_APPLICATION");
				application.setStatus("CANCELLED");
				ObjectNode data = JsonNodeFactory.instance.objectNode().put("cancellationAcknowledgementStatus",
						"ACKNOWLEDGED");
				if (body != null && body.hasNonNull("cancellationId")) {
					data.put("cancellationId", uuid(body, "cancellationId").toString());
				}
				emit(application, "lender.cancellation.acknowledged", data,
						body == null ? NullNode.instance : body.path("originActor"));
			}
			repository.flush();
			return Map.of("status", "CANCELLED", "submissionId", reference, "lenderId", lender(bank));
		});
	}

	public JsonNode details(String bank, String externalId, Jwt jwt) {
		requireBank(bank);
		authorize(jwt, "lender.lookup");
		return p.tx(() -> {
			SimCase application = external(bank, externalId);
			ObjectNode state = readState(application);
			return state.put("status", application.getStatus()).put("lenderApplicationId", application.getExternalId());
		});
	}

	public Map<String, String> command(String bank, String externalId, UUID commandId, JsonNode body, Jwt jwt,
			String correlation) {
		requireBank(bank);
		authorize(jwt, "lender.command");
		String type = text(body, "type");
		if (!body.path("data").isObject()) {
			throw failure(HttpStatus.BAD_REQUEST, "DATA_REQUIRED");
		}
		ObjectNode canonical = JsonNodeFactory.instance.objectNode().put("type", type);
		canonical.set("data", LenderCommandPayloads.command(type, body.path("data")));
		if (body.hasNonNull("originActor"))
			canonical.set("originActor", body.get("originActor"));
		String hash = businessHash(canonical);
		return p.tx(() -> {
			SimCase application = external(bank, externalId);
			ObjectNode state = readState(application);
			ObjectNode commands = map(state, "commands");
			JsonNode previous = commands.get(commandId.toString());
			if (previous != null) {
				require(hash.equals(previous.asText()), "COMMAND_PAYLOAD_CONFLICT");
				return Map.of("status", "ACCEPTED", "commandId", commandId.toString());
			}
			JsonNode data = canonical.path("data");
			JsonNode origin = canonical.path("originActor");
			application.setCommandCorrelationId(
					correlation == null ? storedTrace(application) : trace(correlation, commandId));
			application.setCommandCausationId(commandId);
			require(!Set.of("CANCELLED", "REJECTED", "OFFER_ACCEPTED", "OFFER_DECLINED")
					.contains(application.getStatus()) || type.equals("CANCEL"), "TERMINAL_APPLICATION");
			switch (type) {
			case "DECISION" -> decision(application, state, data, origin);
			case "REQUEST_INFORMATION" -> questions(application, state, data, origin);
			case "ANSWER_INFORMATION" -> answers(application, state, data, origin);
			case "CREATE_OFFER" -> offer(application, state, data, origin);
			case "WITHDRAW_OFFER" -> withdraw(application, state, data, origin);
			case "ACCEPT_OFFER" -> accept(application, state, data, origin);
			case "DECLINE_OFFER" -> {
				ObjectNode offer = storedOffer(state, data);
				require("ACTIVE".equals(offer.path("status").asText()), "OFFER_NOT_ACTIVE");
				offer.put("status", "DECLINED");
				offer.put("version", offer.path("version").asInt() + 1);
				application.setStatus("OFFER_DECLINED");
			}
			case "CANCEL" -> {
				UUID cancellationId = uuid(data, "cancellationId");
				require(!Set.of("OFFER_ACCEPTED", "OFFER_DECLINED", "REJECTED").contains(application.getStatus()),
						"TERMINAL_APPLICATION");
				application.setStatus("CANCELLED");
				emit(application, "lender.cancellation.acknowledged",
						JsonNodeFactory.instance.objectNode().put("cancellationAcknowledgementStatus", "ACKNOWLEDGED")
								.put("cancellationId", cancellationId.toString()),
						origin);
			}
			default -> throw failure(HttpStatus.BAD_REQUEST, "UNKNOWN_COMMAND");
			}
			commands.put(commandId.toString(), hash);
			save(application, state);
			return Map.of("status", "ACCEPTED", "commandId", commandId.toString());
		});
	}

	private void decision(SimCase application, ObjectNode state, JsonNode data, JsonNode origin) {
		uuid(data, "decisionId");
		String decision = text(data, "decision");
		require(Set.of("APPROVED", "REJECTED", "MANUAL_REVIEW", "ACTION_REQUIRED").contains(decision),
				"INVALID_DECISION");
		require(!"APPROVED".equals(application.getStatus()), "WITHDRAW_OFFER_FIRST");
		if (decision.equals("REJECTED")) {
			require(text(data, "reasonCode").matches("[A-Z][A-Z0-9_]{0,99}"), "INVALID_REJECTION_REASON_CODE");
		}
		if (Set.of("MANUAL_REVIEW", "ACTION_REQUIRED").contains(decision)) {
			String requestId = uuid(data, "informationRequestId").toString();
			JsonNode list = data.path("questions");
			require(list.isArray() && !list.isEmpty() && list.size() <= 50, "QUESTIONS_REQUIRED");
			Set<String> ids = new HashSet<>();
			for (JsonNode question : list)
				require(ids.add(text(question, "questionId")) && text(question, "prompt").length() <= 1000,
						"INVALID_QUESTIONS");
			require(!map(state, "informationRequests").has(requestId), "INFORMATION_REQUEST_EXISTS");
		}
		if (data.has("offer")) {
			if (!decision.equals("APPROVED") || !data.path("offer").isObject()) {
				throw failure(HttpStatus.BAD_REQUEST, "INVALID_DECISION_OFFER");
			}
			validateOffer(data.path("offer"));
			if (decimal(data.path("offer"), "offeredAmount", true, 2).compareTo(application.getRequestedAmount()) > 0) {
				throw failure(HttpStatus.BAD_REQUEST, "OFFER_EXCEEDS_REQUEST");
			}
		}
		ObjectNode recorded = (ObjectNode) data.deepCopy();
		Instant decidedAt = data.hasNonNull("decidedAt") ? instant(data, "decidedAt") : Instant.now();
		if (decidedAt.isAfter(Instant.now().plusSeconds(60))) {
			throw failure(HttpStatus.BAD_REQUEST, "DECISION_TIME_IN_FUTURE");
		}
		recorded.put("decidedAt", decidedAt.toString());
		application.setStatus(decision.equals("APPROVED") ? "UNDER_REVIEW" : decision);
		state.withArray("decisions").add(recorded.<JsonNode>deepCopy());
		emit(application, "lender.decision.received", recorded, origin);
		if (Set.of("ACTION_REQUIRED", "MANUAL_REVIEW").contains(decision)) {
			questions(application, state, data, origin);
		}
		if (decision.equals("APPROVED") && data.path("offer").isObject()) {
			offer(application, state, data.path("offer"), origin);
		}
	}

	private void questions(SimCase application, ObjectNode state, JsonNode data, JsonNode origin) {
		String id = uuid(data, "informationRequestId").toString();
		require(!"APPROVED".equals(application.getStatus()), "OFFER_ALREADY_AVAILABLE");
		JsonNode list = data.path("questions");
		if (!list.isArray() || list.isEmpty() || list.size() > 50) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_QUESTIONS");
		}
		Set<String> ids = new HashSet<>();
		for (JsonNode question : list) {
			require(ids.add(text(question, "questionId")), "DUPLICATE_QUESTION");
			if (text(question, "prompt").length() > 1000) {
				throw failure(HttpStatus.BAD_REQUEST, "QUESTION_TOO_LONG");
			}
		}
		require(!map(state, "informationRequests").has(id), "INFORMATION_REQUEST_EXISTS");
		map(state, "informationRequests").set(id, data.deepCopy());
		application.setStatus("ACTION_REQUIRED");
		emit(application, "lender.information.requested", data, origin);
	}

	private void answers(SimCase application, ObjectNode state, JsonNode data, JsonNode origin) {
		String id = uuid(data, "informationRequestId").toString();
		JsonNode request = map(state, "informationRequests").get(id);
		require(request != null, "INFORMATION_REQUEST_NOT_FOUND");
		require(!map(state, "answers").has(id), "ALREADY_ANSWERED");
		Set<String> expected = new HashSet<>();
		Set<String> supplied = new HashSet<>();
		request.path("questions").forEach(question -> expected.add(question.path("questionId").asText()));
		JsonNode list = data.path("answers");
		if (data.has("syntheticDocumentReferences")) {
			if (!data.path("syntheticDocumentReferences").isArray()) {
				throw failure(HttpStatus.BAD_REQUEST, "INVALID_SYNTHETIC_REFERENCES");
			}
			for (JsonNode reference : data.path("syntheticDocumentReferences")) {
				if (!reference.isTextual() || !reference.asText().matches("synthetic://[A-Za-z0-9_./-]{1,200}")) {
					throw failure(HttpStatus.BAD_REQUEST, "INVALID_SYNTHETIC_REFERENCES");
				}
			}
		}
		if (!list.isArray() || list.isEmpty() || list.size() > 50) {
			throw failure(HttpStatus.BAD_REQUEST, "ANSWERS_REQUIRED");
		}
		for (JsonNode answer : list) {
			require(supplied.add(text(answer, "questionId")), "DUPLICATE_ANSWER");
			if (answer.has("answer") || answer.has("documentReferences")) {
				throw failure(HttpStatus.BAD_REQUEST, "USE_ANSWER_TEXT_AND_SYNTHETIC_REFERENCES");
			}
			String answerText = text(answer, "answerText");
			if (answerText.length() > 4000) {
				throw failure(HttpStatus.BAD_REQUEST, "ANSWER_TOO_LONG");
			}
		}
		require(expected.equals(supplied), "QUESTION_ANSWER_MISMATCH");
		map(state, "answers").set(id, data.deepCopy());
		boolean allAnswered = true;
		for (Iterator<String> iterator = map(state, "informationRequests").fieldNames(); iterator.hasNext();) {
			if (!map(state, "answers").has(iterator.next())) {
				allAnswered = false;
			}
		}
		if (allAnswered && "ACTION_REQUIRED".equals(application.getStatus())) {
			application.setStatus("UNDER_REVIEW");
		}
		emit(application, "lender.information.answered", data, origin);
	}

	private void offer(SimCase application, ObjectNode state, JsonNode input, JsonNode origin) {
		require(Set.of("UNDER_REVIEW", "MANUAL_REVIEW", "ACTION_REQUIRED").contains(application.getStatus()),
				"CANNOT_CREATE_OFFER");
		validateOffer(input);
		ObjectNode data = (ObjectNode) input.deepCopy();
		String id = uuid(data, "offerId").toString();
		require(!map(state, "offers").has(id), "OFFER_EXISTS");
		require("INR".equals(text(data, "currency")), "UNSUPPORTED_CURRENCY");
		require("FIXED".equals(text(data, "interestRateType")), "UNSUPPORTED_RATE_TYPE");
		require("MONTHLY".equals(text(data, "repaymentFrequency")), "UNSUPPORTED_REPAYMENT");
		application.setOfferedAmount(decimal(data, "offeredAmount", true, 2));
		application.setOfferFees(decimal(data, "feesAmount", false, 2));
		application.setOfferRate(decimal(data, "annualInterestRatePercent", false, 4));
		require(application.getOfferedAmount().compareTo(application.getRequestedAmount()) <= 0,
				"OFFER_EXCEEDS_REQUEST");
		require(application.getOfferRate().compareTo(new BigDecimal("100")) <= 0, "INVALID_RATE");
		require(data.path("tenureMonths").asInt() > 0, "INVALID_TENURE");
		require(instant(data, "expiresAt").isAfter(Instant.now()), "OFFER_ALREADY_EXPIRED");
		data.put("offeredAmount", application.getOfferedAmount().toPlainString());
		data.put("feesAmount", application.getOfferFees().toPlainString());
		data.put("annualInterestRatePercent", application.getOfferRate().toPlainString());
		data.put("version", 0);
		data.put("status", "ACTIVE");
		map(state, "offers").set(id, data);
		application.setStatus("APPROVED");
		emit(application, "loan.offer.created", data, origin);
	}

	private void validateOffer(JsonNode data) {
		if (!data.isObject()) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_OFFER_TERMS");
		}
		uuid(data, "offerId");
		decimal(data, "offeredAmount", true, 2);
		decimal(data, "feesAmount", false, 2);
		BigDecimal rate = decimal(data, "annualInterestRatePercent", false, 4);
		if (!"INR".equals(text(data, "currency")) || !"FIXED".equals(text(data, "interestRateType"))
				|| !"MONTHLY".equals(text(data, "repaymentFrequency")) || !data.path("tenureMonths").isIntegralNumber()
				|| !data.path("tenureMonths").canConvertToInt() || data.path("tenureMonths").asInt() < 1
				|| data.path("tenureMonths").asInt() > 120 || rate.compareTo(new BigDecimal("100")) > 0
				|| (data.has("version")
						&& (!data.path("version").isIntegralNumber() || data.path("version").asLong() != 0))) {
			throw failure(HttpStatus.BAD_REQUEST, "INVALID_OFFER_TERMS");
		}
		if (text(data, "termsReference").length() > 255) {
			throw failure(HttpStatus.BAD_REQUEST, "TERMS_REFERENCE_TOO_LONG");
		}
		if (!instant(data, "expiresAt").isAfter(Instant.now())) {
			throw failure(HttpStatus.BAD_REQUEST, "OFFER_ALREADY_EXPIRED");
		}
	}

	private ObjectNode storedOffer(ObjectNode state, JsonNode data) {
		JsonNode found = map(state, "offers").get(uuid(data, "offerId").toString());
		require(found != null, "OFFER_NOT_FOUND");
		return (ObjectNode) found;
	}

	private void withdraw(SimCase application, ObjectNode state, JsonNode data, JsonNode origin) {
		ObjectNode offer = storedOffer(state, data);
		require("ACTIVE".equals(offer.path("status").asText()), "OFFER_NOT_ACTIVE");
		offer.put("status", "WITHDRAWN");
		offer.put("version", offer.path("version").asInt() + 1);
		application.setStatus("UNDER_REVIEW");
		emit(application, "loan.offer.withdrawn", data, origin);
	}

	private void accept(SimCase application, ObjectNode state, JsonNode data, JsonNode origin) {
		uuid(data, "acceptanceId");
		ObjectNode offer = storedOffer(state, data);
		require("ACTIVE".equals(offer.path("status").asText()), "OFFER_NOT_ACTIVE");
		require(data.path("expectedOfferVersion").isIntegralNumber()
				&& data.path("expectedOfferVersion").canConvertToLong()
				&& data.path("expectedOfferVersion").asLong() >= 0
				&& data.path("expectedOfferVersion").asInt(0) == offer.path("version").asInt(),
				"OFFER_VERSION_CONFLICT");
		Instant accepted = instant(data, "acceptedAt");
		require(!accepted.isAfter(Instant.now().plusSeconds(60)) && accepted.isBefore(instant(offer, "expiresAt")),
				"OFFER_EXPIRED");
		offer.put("status", "ACCEPTED");
		offer.put("version", offer.path("version").asInt() + 1);
		application.setStatus("OFFER_ACCEPTED");
		ObjectNode acknowledgement = (ObjectNode) data.deepCopy();
		acknowledgement.put("lenderAcknowledgementStatus", "ACKNOWLEDGED");
		emit(application, "loan.offer.acknowledged", acknowledgement, origin);
	}

	private void emit(SimCase application, String type, JsonNode data, JsonNode origin) {
		SimCallback row = new SimCallback();
		row.setId(UUID.randomUUID());
		row.setCaseKey(application.getId());
		row.setLenderCode(application.getLenderCode());
		row.setLenderId(application.getLenderId());
		row.setSequence(application.incrementSequence());
		ObjectNode event = JsonNodeFactory.instance.objectNode();
		event.put("eventId", row.getId().toString());
		event.put("lenderSequence", row.getSequence());
		event.put("applicationId", application.getApplicationId().toString());
		event.put("submissionId", application.getSubmissionId().toString());
		event.put("applicationRevision", application.getRevision());
		event.put("lenderId", application.getLenderId());
		event.put("lenderApplicationId", application.getExternalId());
		event.put("eventType", type);
		event.put("occurredAt", Instant.now().toString());
		event.put("correlationId", application.getCommandCorrelationId() == null ? storedTrace(application)
				: application.getCommandCorrelationId());
		JsonNode originalRequest = readState(application).path("request");
		UUID causal = application.getCommandCausationId();
		if (causal == null && originalRequest.hasNonNull("causationId")) {
			causal = uuid(originalRequest, "causationId");
		}
		if (causal != null) {
			event.put("causationId", causal.toString());
		}
		event.set("data", data.deepCopy());
		if (origin == null || origin.isMissingNode() || origin.isNull()) {
			origin = readState(application).path("request").path("originActor");
		}
		if (origin != null && origin.isObject()) {
			event.set("originActor", origin.deepCopy());
		}
		row.setPayload(p.write(event));
		row.setStatus("PENDING");
		row.setNextAttempt(Instant.now());
		repository.save(row);
	}

	public void callbacks() {
		try {
			callbacksForBank(bankCode);
		} catch (RuntimeException failure) {
			log.warn("Lender callback poll failed bank={} error={}", bankCode, failure.getClass().getSimpleName());
		}
	}

	public void callbacksForBank(String bank) {
		requireBank(bank);
		List<UUID> ids = p.tx(() -> repository.dueCallbackIdsQuery().setParameter("bank", bank)
				.setParameter("now", Instant.now()).setMaxResults(20).getResultList());
		for (UUID id : ids) {
			Delivery delivery = p.tx(() -> {
				SimCallback callback = repository.find(SimCallback.class, id, LockModeType.PESSIMISTIC_WRITE);
				if (callback == null || !bank.equals(callback.getLenderCode())
						|| !Set.of("PENDING", "SENDING").contains(callback.getStatus())
						|| callback.getNextAttempt().isAfter(Instant.now())) {
					return null;
				}
				long earlier = repository.countEarlierCallbacksQuery().setParameter("key", callback.getCaseKey())
						.setParameter("seq", callback.getSequence()).getSingleResult();
				if (earlier > 0) {
					return null;
				}
				callback.setStatus("SENDING");
				callback.setNextAttempt(Instant.now().plusSeconds(60));
				callback.setLeaseId(UUID.randomUUID());
				return new Delivery(callback.getId(), callback.getLeaseId(), callback.getLenderCode(),
						callback.getLenderId(), parse(callback.getPayload()));
			});
			if (delivery == null) {
				continue;
			}
			boolean sent = false;
			try {
				p.call(delivery.bank() + "-callback", HttpMethod.POST,
						callbackBase + "/api/v1/lenders/" + delivery.lenderId() + "/callbacks", delivery.payload(),
						delivery.id().toString(), text(delivery.payload(), "correlationId"));
				sent = true;
			} catch (RuntimeException exception) {
				log.warn("Callback {} delivery failed ({})", id, exception.getClass().getSimpleName());
			}
			final boolean delivered = sent;
			p.tx(() -> {
				SimCallback callback = repository.find(SimCallback.class, id, LockModeType.PESSIMISTIC_WRITE);
				if (callback != null && "SENDING".equals(callback.getStatus())
						&& Objects.equals(callback.getLeaseId(), delivery.lease())) {
					if (delivered) {
						callback.setStatus("SENT");
						callback.setFailure(null);
					} else {
						callback.incrementAttempts();
						callback.setStatus(callback.getAttempts() >= 12 ? "DEAD" : "PENDING");
						callback.setFailure("CALLBACK_DELIVERY_FAILED");
						callback.setNextAttempt(
								Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, callback.getAttempts()))));
					}
				}
				return null;
			});
		}
	}

	public Map<String, String> replay(String bank, UUID eventId, Jwt jwt) {
		requireBank(bank);
		authorize(jwt, "ops.replay");
		return p.tx(() -> {
			SimCallback callback = repository.find(SimCallback.class, eventId, LockModeType.PESSIMISTIC_WRITE);
			if (callback == null || !bank.equals(callback.getLenderCode())) {
				throw failure(HttpStatus.NOT_FOUND, "CALLBACK_NOT_FOUND");
			}
			require("DEAD".equals(callback.getStatus()), "CALLBACK_NOT_DEAD");
			callback.setStatus("PENDING");
			callback.setAttempts(0);
			callback.setNextAttempt(Instant.now());
			callback.setFailure(null);
			return Map.of("eventId", eventId.toString(), "status", "PENDING");
		});
	}
}
