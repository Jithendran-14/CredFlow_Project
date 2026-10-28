package com.credflow.controller;

import com.credflow.api.WorkflowApi;
import com.credflow.dto.InformationAnswersRequest;
import com.credflow.dto.OfferActionRequest;
import com.credflow.dto.RevisionGuardRequest;
import com.credflow.dto.WorkflowVersionRequest;
import com.credflow.platform.Platform.*;
import com.credflow.service.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkflowController implements WorkflowApi {

	private final WorkflowService service;
	private final com.credflow.service.OfferDocuments documents;

	public WorkflowController(WorkflowService service, com.credflow.service.OfferDocuments documents) {
		this.documents = documents;
		this.service = service;
	}

	@Override
	public Object workflow(UUID id, Jwt jwt) {
		return service.workflow(id, jwt);
	}

	@Override
	public Object offers(UUID id, int page, int size, Jwt jwt) {
		return service.offers(id, page, size, jwt);
	}

	@Override
	public Object questions(UUID id, int page, int size, Jwt jwt) {
		return service.questions(id, page, size, jwt);
	}

	@Override
	public Object submissions(int page, int size, String lenderId, Jwt jwt) {
		return service.submissions(page, size, lenderId, jwt);
	}

	@Override
	public Object submission(UUID id, Jwt jwt) {
		return service.submission(id, jwt);
	}

	@Override
	public ResponseEntity<?> accept(UUID id, OfferActionRequest body, String key, Jwt jwt) {
		// Phase 0 excludes mandatory document upload; attached PDFs remain optional
		// supporting material.
		var reply = service.offerAction(id, body, key, jwt, true);
		return ResponseEntity.status(reply.status()).body(reply.body());
	}

	@Override
	public ResponseEntity<?> decline(UUID id, OfferActionRequest body, String key, Jwt jwt) {
		var reply = service.offerAction(id, body, key, jwt, false);
		return ResponseEntity.status(reply.status()).body(reply.body());
	}

	@Override
	public ResponseEntity<?> answer(UUID id, InformationAnswersRequest body, String key, Jwt jwt) {
		var reply = service.answer(id, body, key, jwt);
		return ResponseEntity.status(reply.status()).body(reply.body());
	}

	@Override
	public ResponseEntity<?> cancel(UUID id, WorkflowVersionRequest body, String key, Jwt jwt) {
		var reply = service.command(id, body, key, jwt, false);
		return ResponseEntity.status(reply.status()).body(reply.body());
	}

	@Override
	public ResponseEntity<?> refresh(UUID id, WorkflowVersionRequest body, String key, Jwt jwt) {
		var reply = service.command(id, body, key, jwt, true);
		return ResponseEntity.status(reply.status()).body(reply.body());
	}

	@Override
	public Object authorization(UUID id, Jwt jwt) {
		return service.authorization(id, jwt);
	}

	@Override
	public Object correction(UUID id, RevisionGuardRequest body, Jwt jwt) {
		return service.correction(id, body, jwt, false);
	}

	@Override
	public Object abort(UUID id, RevisionGuardRequest body, Jwt jwt) {
		return service.correction(id, body, jwt, true);
	}

	@Override
	public Object consent(UUID id, JsonNode body, Jwt jwt) {
		return service.consentOperation(id, body, jwt);
	}
}
