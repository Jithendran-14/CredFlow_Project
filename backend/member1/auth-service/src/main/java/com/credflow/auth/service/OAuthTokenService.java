package com.credflow.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.credflow.auth.dto.OAuthTokenResponse;
import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.exception.OAuthTokenException;
import com.credflow.auth.repository.AuthServiceClientRepository;
import com.credflow.auth.repository.AuthUserRepository;

@Service
public class OAuthTokenService {

    private static final Map<String, String> CLIENT_ROLES = Map.of(
            "application-service", "APPLICATION_SERVICE",
            "consent-verification-service", "VERIFICATION_SERVICE",
            "eligibility-service", "ELIGIBILITY_SERVICE",
            "orchestration-service", "ORCHESTRATION_SERVICE",
            "integration-service", "INTEGRATION_SERVICE",
            "mock-bank-a", "LENDER_SERVICE",
            "mock-bank-c", "LENDER_SERVICE",
            "mock-nbfc-b", "LENDER_SERVICE"
    );

    private final AuthServiceClientRepository clientRepository;
    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public OAuthTokenService(
            AuthServiceClientRepository clientRepository,
            AuthUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public OAuthTokenResponse issueToken(
            String authorization,
            MultiValueMap<String, String> form) {

        Credentials credentials = parseBasicCredentials(authorization);

        var client = clientRepository
                .findByClientId(credentials.clientId())
                .orElseThrow(OAuthTokenService::invalidClient);

        AuthUser user = userRepository.findById(client.getUserId())
                .orElseThrow(OAuthTokenService::invalidClient);

        boolean marketplaceClient = "MARKETPLACE".equals(user.getUserRole()) && hasText(user.getMarketplaceId())
                && ("marketplace-submission-" + user.getMarketplaceId()).equals(client.getClientId())
                && "application.submit".equals(client.getScopes());
        String expectedRole = marketplaceClient ? "MARKETPLACE" : CLIENT_ROLES.get(client.getClientId());

        if (expectedRole == null
                || !expectedRole.equals(user.getUserRole())
                || !"ACTIVE".equals(user.getAccountStatus())
                || hasText(user.getSellerId())
                || (!marketplaceClient && hasText(user.getMarketplaceId())) || hasText(user.getLenderId())) {

            throw invalidClient();
        }

        boolean matches;

        try {
            matches = passwordEncoder.matches(
                    credentials.clientSecret(),
                    user.getPasswordHash()
            );
        } catch (IllegalArgumentException exception) {
            throw invalidClient();
        }

        if (!matches) {
            throw invalidClient();
        }

        if (form == null
                || form.containsKey("client_id")
                || form.containsKey("client_secret")) {

            throw new OAuthTokenException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request");
        }

        String grantType = singleValue(form, "grant_type", true);

        if (!"client_credentials".equals(grantType)) {
            throw new OAuthTokenException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported_grant_type");
        }

        if (!hasText(client.getScopes())) {
            throw invalidClient();
        }

        Set<String> allowedScopes = splitScopes(client.getScopes());

        String requestedScope = singleValue(form, "scope", false);

        Set<String> grantedScopes;

        if (requestedScope == null) {
            grantedScopes = allowedScopes;
        } else {
            grantedScopes = splitScopes(requestedScope);

            if (grantedScopes.isEmpty()
                    || !allowedScopes.containsAll(grantedScopes)) {

                throw new OAuthTokenException(
                        HttpStatus.BAD_REQUEST,
                        "invalid_scope");
            }
        }

        String scope = String.join(" ", grantedScopes);

        String token = jwtService.generateClientCredentialsToken(
                user,
                client.getClientId(),
                scope
        );

        return new OAuthTokenResponse(
                token,
                "Bearer",
                jwtService.getExpirySeconds(),
                scope
        );
    }

    private Credentials parseBasicCredentials(String authorization) {

        if (authorization == null
                || !authorization.regionMatches(
                        true, 0, "Basic ", 0, 6)) {
            throw invalidClient();
        }

        try {
            String encoded = authorization.substring(6).trim();

            String decoded = new String(
                    Base64.getDecoder().decode(encoded),
                    StandardCharsets.ISO_8859_1
            );

            int separator = decoded.indexOf(':');

            if (separator <= 0 || separator == decoded.length() - 1) {
                throw invalidClient();
            }

            String clientId = decoded.substring(0, separator);
            String clientSecret = decoded.substring(separator + 1);

            if (clientId.length() > 100 || clientSecret.length() > 128) {
                throw invalidClient();
            }

            return new Credentials(clientId, clientSecret);

        } catch (IllegalArgumentException exception) {
            throw invalidClient();
        }
    }

    private String singleValue(
            MultiValueMap<String, String> form,
            String name,
            boolean required) {

        List<String> values = form.get(name);

        if (values == null) {
            if (required) {
                throw new OAuthTokenException(
                        HttpStatus.BAD_REQUEST,
                        "invalid_request");
            }
            return null;
        }

        if (values.size() != 1 || !hasText(values.get(0))) {
            throw new OAuthTokenException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request");
        }

        return values.get(0);
    }

    private Set<String> splitScopes(String scope) {
        return Arrays.stream(scope.trim().split("\\s+"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static OAuthTokenException invalidClient() {
        return new OAuthTokenException(
                HttpStatus.UNAUTHORIZED,
                "invalid_client");
    }

    private record Credentials(
            String clientId,
            String clientSecret
    ) {
    }
}