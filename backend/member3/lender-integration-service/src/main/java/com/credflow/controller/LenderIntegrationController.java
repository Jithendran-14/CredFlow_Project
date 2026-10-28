package com.credflow.controller;

import com.credflow.api.LenderIntegrationApi;
import com.credflow.dto.CallbackAcceptedResponse;
import com.credflow.dto.OperationReplayResponse;
import com.credflow.dto.SubmissionQueuedResponse;
import com.credflow.platform.Platform.Actor;
import com.credflow.platform.Platform;
import com.credflow.service.LenderIntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class LenderIntegrationController implements LenderIntegrationApi {

	private final LenderIntegrationService engine;

	private final Platform p;

	public LenderIntegrationController(LenderIntegrationService engine, Platform p) {
		this.engine = engine;
		this.p = p;
	}

	@Override
	public ResponseEntity<JsonNode> submit(JsonNode request, Jwt jwt, String correlation) {
		p.machine(jwt, "integration.write", "orchestration-service");
		return ResponseEntity.accepted().body(engine.reserve(engine.traced(request, correlation)));
	}

	@Override
	public JsonNode dispatch(UUID id, Jwt jwt) {
		p.machine(jwt, "integration.write", "orchestration-service");
		return engine.status(id);
	}

	@Override
	public ResponseEntity<JsonNode> internalCommand(UUID id, String operation, JsonNode data, UUID commandId, Jwt jwt,
			String correlation) {
		p.machine(jwt, "integration.write", "orchestration-service");
		String type = switch (operation) {
		case "information-responses" -> "ANSWER_INFORMATION";
		case "offer-acceptances" -> "ACCEPT_OFFER";
		case "offer-declines" -> "DECLINE_OFFER";
		case "cancellation" -> "CANCEL";
		default -> throw new IllegalArgumentException("UNKNOWN_OPERATION");
		};
		Actor origin = data.hasNonNull("originActor") ? p.read(data.get("originActor"), Actor.class) : null;
		engine.command(id, commandId, type, engine.traced(data, correlation), origin);
		return ResponseEntity.accepted().body(p.json(new SubmissionQueuedResponse(id, commandId, "QUEUED")));
	}

	@Override
	public ResponseEntity<JsonNode> officerCommand(UUID id, String operation, JsonNode data, UUID commandId, Jwt jwt,
			String correlation) {
		p.requireScope(jwt, "lender.review");
		p.owner(jwt, null, engine.lender(id), false);
		String type = switch (operation) {
		case "decisions" -> "DECISION";
		case "information-requests" -> "REQUEST_INFORMATION";
		case "offers" -> "CREATE_OFFER";
		case "offer-withdrawals" -> "WITHDRAW_OFFER";
		default -> throw new IllegalArgumentException("UNKNOWN_OPERATION");
		};
		ObjectNode trusted = engine.traced(data, correlation);
		trusted.remove(List.of("originActor", "decidedAt", "causationId"));
		engine.command(id, commandId, type, trusted, p.actor(jwt));
		return ResponseEntity.accepted().body(p.json(new SubmissionQueuedResponse(id, commandId, "QUEUED")));
	}

	@Override
	public ResponseEntity<JsonNode> callback(String lenderId, JsonNode body, Jwt jwt, String correlation) {
		p.machine(jwt, "lender.callback", engine.callbackClient(lenderId));
		engine.callback(lenderId, body, new Actor("SYSTEM_CLIENT", p.client(jwt), lenderId), correlation);
		return ResponseEntity.accepted()
				.body(p.json(new CallbackAcceptedResponse(LenderIntegrationService.uuid(body, "eventId"), "PENDING")));
	}

	@Override
	public ResponseEntity<JsonNode> replay(UUID id, Jwt jwt) {
		p.requireScope(jwt, "ops.replay");
		if (!p.hasRole(jwt, "ADMIN")) {
			throw new org.springframework.security.access.AccessDeniedException("ROLE_REQUIRED");
		}
		engine.replay(id);
		return ResponseEntity.accepted().body(p.json(new OperationReplayResponse(id, "PENDING")));
	}

	@Override
	public JsonNode task(UUID id, Jwt jwt) {
		p.requireScope(jwt, "ops.replay");
		if (!p.hasRole(jwt, "ADMIN")) {
			throw new org.springframework.security.access.AccessDeniedException("ROLE_REQUIRED");
		}
		return engine.taskStatus(id);
	}
}
