package com.credflow.auth.controller;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.credflow.auth.exception.OAuthTokenException;
import com.credflow.auth.service.OAuthTokenService;

@RestController
public class OAuthTokenController {

    private final OAuthTokenService tokenService;

    public OAuthTokenController(OAuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping(
            value = "/oauth2/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> token(
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization,
            @RequestBody(required = false)
            MultiValueMap<String, String> form) {

        try {
            var response = tokenService.issueToken(
                    authorization,
                    form
            );

            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(response);

        } catch (OAuthTokenException exception) {

            var response = ResponseEntity
                    .status(exception.getStatus())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache");

            if (exception.getStatus() == HttpStatus.UNAUTHORIZED) {
                response.header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Basic realm=\"credflow-token\""
                );
            }

            return response.body(
                    Map.of("error", exception.getErrorCode())
            );
        }
    }
}