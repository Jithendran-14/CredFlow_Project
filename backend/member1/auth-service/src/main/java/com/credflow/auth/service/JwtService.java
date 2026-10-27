package com.credflow.auth.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.repository.AuthServiceClientRepository;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final AuthServiceClientRepository serviceClientRepository;
    private final String issuer;
    private final String audience;
    private final long expiryMinutes;

    public JwtService(
            JwtEncoder jwtEncoder,
            AuthServiceClientRepository serviceClientRepository,
            @Value("${credflow.jwt.issuer}") String issuer,
            @Value("${credflow.jwt.audience}") String audience,
            @Value("${credflow.jwt.expiry-minutes:15}") long expiryMinutes) {

        this.jwtEncoder = jwtEncoder;
        this.serviceClientRepository = serviceClientRepository;
        this.issuer = issuer;
        this.audience = audience;
        this.expiryMinutes = expiryMinutes;
    }

    public String generateToken(AuthUser user) {
        if ("MARKETPLACE".equals(user.getUserRole()) && user.getUserId()!=null && serviceClientRepository.findById(user.getUserId()).isPresent())
            throw new com.credflow.auth.exception.InvalidCredentialsException();

        Instant now = Instant.now();

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getPublicUserId())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES))
                .claim("username", user.getUsername())
                .claim("roles", List.of(user.getUserRole()))
                .claim("userId", user.getPublicUserId());

        if (user.getSellerId() != null
                && !user.getSellerId().isBlank()) {
            claimsBuilder.claim("sellerId", user.getSellerId());
        }

        if (user.getMarketplaceId() != null
                && !user.getMarketplaceId().isBlank()) {
            claimsBuilder.claim(
                    "marketplaceId",
                    user.getMarketplaceId()
            );
        }

        if ("LENDER_OFFICER".equals(user.getUserRole())) {
            if (user.getLenderId() == null || user.getLenderId().isBlank())
                throw new IllegalStateException("Lender identity is missing");
            claimsBuilder.claim("lenderId", user.getLenderId());
            claimsBuilder.claim("scope", "lender.review");
        }
        addServiceClaims(user, claimsBuilder);

        return jwtEncoder.encode(
                JwtEncoderParameters.from(claimsBuilder.build())
        ).getTokenValue();
    }

    private void addServiceClaims(
            AuthUser user,
            JwtClaimsSet.Builder claimsBuilder) {

        String role = user.getUserRole();

        boolean serviceAccount =
                "VERIFICATION_SERVICE".equals(role)
                || "ELIGIBILITY_SERVICE".equals(role);

        // Human accounts never receive machine grants through this path.
        if (!serviceAccount) {
            return;
        }

        if (user.getSellerId() != null
                && !user.getSellerId().isBlank()
                || user.getMarketplaceId() != null
                && !user.getMarketplaceId().isBlank()) {

            throw new IllegalStateException(
                    "Service account must not contain seller or marketplace IDs");
        }

        serviceClientRepository.findById(user.getUserId())
                .ifPresent(client -> {

                    String expectedClientId =
                            "VERIFICATION_SERVICE".equals(role)
                                    ? "consent-verification-service"
                                    : "eligibility-service";

                    if (!expectedClientId.equals(client.getClientId())) {
                        throw new IllegalStateException(
                                "Service client identity does not match account role");
                    }

                    String scopes = client.getScopes();

                    if (scopes == null || scopes.isBlank()) {
                        throw new IllegalStateException(
                                "Registered service client requires configured scopes");
                    }

                    claimsBuilder.claim(
                            "client_id",
                            client.getClientId()
                    );

                    claimsBuilder.claim(
                            "scope",
                            scopes.trim().replaceAll("\\s+", " ")
                    );
                });
    }

    public long getExpirySeconds() {
        return expiryMinutes * 60;
    }
    public String generateClientCredentialsToken(
            AuthUser user,
            String clientId,
            String scope) {

        Instant now = Instant.now();

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getPublicUserId())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES))
                .claim("username", user.getUsername())
                .claim("userId", user.getPublicUserId())
                .claim("roles", List.of(user.getUserRole()))
                .claim("client_id", clientId)
                .claim("scope", scope);
        if ("MARKETPLACE".equals(user.getUserRole())) builder.claim("marketplaceId", user.getMarketplaceId());
        JwtClaimsSet claims=builder.build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(claims)
        ).getTokenValue();
    }
}