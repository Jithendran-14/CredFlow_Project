package com.credflow.controller;

import com.credflow.api.NbfcBApi;
import com.credflow.service.NbfcBSimulatorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class NbfcBController implements NbfcBApi {

    private final NbfcBSimulatorService service;

    public NbfcBController(NbfcBSimulatorService service) {
        this.service = service;
    }

    @Override
    public Object submit(String bank, JsonNode wire, Jwt jwt, String correlation) {
        return service.submit(bank, wire, jwt, correlation);
    }

    @Override
    public Object lookup(String bank, UUID reference, Jwt jwt) {
        return service.lookup(bank, reference, jwt);
    }

    @Override
    public Map<String, Object> cancelReference(
        String bank,
        UUID reference,
        JsonNode body,
        Jwt jwt,
        String correlation
    ) {
        return service.cancelReference(bank, reference, body, jwt, correlation);
    }

    @Override
    public JsonNode details(String bank, String externalId, Jwt jwt) {
        return service.details(bank, externalId, jwt);
    }

    @Override
    public Map<String, String> command(
        String bank,
        String externalId,
        UUID commandId,
        JsonNode body,
        Jwt jwt,
        String correlation
    ) {
        return service.command(bank, externalId, commandId, body, jwt, correlation);
    }

    @Override
    public Map<String, String> replay(String bank, UUID eventId, Jwt jwt) {
        return service.replay(bank, eventId, jwt);
    }
}
