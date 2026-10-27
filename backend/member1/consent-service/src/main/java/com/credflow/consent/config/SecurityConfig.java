package com.credflow.consent.config;

import java.util.ArrayList;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.credflow.consent.service.FinancialRetrievalService;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/internal/v1/security/check"
                ).hasAnyRole(
                    "ELIGIBILITY_SERVICE",
                    "VERIFICATION_SERVICE"
                )

                .requestMatchers(
                    HttpMethod.GET,
                    "/internal/v1/security/machine-check"
                ).access((authentication, context) ->
                    machineAccess(
                        authentication.get(),
                        "consent-verification-service",
                        "ROLE_VERIFICATION_SERVICE",
                        "SCOPE_workflow.consent"
                    )
                )

                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/internal/verifications"
                ).access((authentication, context) ->
                    machineAccess(
                        authentication.get(),
                        "orchestration-service",
                        "ROLE_ORCHESTRATION_SERVICE",
                        "SCOPE_verification.request"
                    )
                )

                .requestMatchers(
                    HttpMethod.POST,
                    "/internal/v1/applications/*/financial-data/retrievals"
                ).access((authentication, context) ->
                    machineAccess(
                        authentication.get(),
                        "eligibility-service",
                        "ROLE_ELIGIBILITY_SERVICE",
                        "SCOPE_financial-data.read"
                    )
                )

                .requestMatchers(
                	    HttpMethod.POST,
                	    "/api/v1/applications/*/consents",
                	    "/api/v1/applications/*/consents/revoke",
                	    "/api/v1/applications/*/financial-consents",
                	    "/api/v1/applications/*/financial-consents/revoke"
                	).hasRole("SELLER")

                	.requestMatchers(
                	    HttpMethod.PUT,
                	    "/api/v1/applications/*/consents"
                	).hasRole("SELLER")
                .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/consents", "/api/v1/applications/*/financial-data-availability").hasRole("SELLER")
                .requestMatchers(HttpMethod.GET,"/internal/v1/applications/*/financial-permission")
                .access((authentication, context) -> machineAccess(authentication.get(),
                    "orchestration-service","ROLE_ORCHESTRATION_SERVICE","SCOPE_verification.request"))
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(
                        jwtAuthenticationConverter()
                    )
                )
            );

        return http.build();
    }

    private AuthorizationDecision machineAccess(
            Authentication authentication,
            String clientId,
            String role,
            String scope) {

        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return new AuthorizationDecision(false);
        }

        boolean allowed = jwt.isAuthenticated()
                && clientId.equals(
                        FinancialRetrievalService.clientIdentity(
                                jwt.getToken()))
                && hasAuthority(jwt, role)
                && hasAuthority(jwt, scope);

        return new AuthorizationDecision(allowed);
    }

    private boolean hasAuthority(
            JwtAuthenticationToken jwt,
            String authority) {

        return jwt.getAuthorities().stream()
                .anyMatch(value ->
                        authority.equals(value.getAuthority()));
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {

        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");

        var scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        scopes.setAuthorityPrefix("SCOPE_");

        var converter = new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<GrantedAuthority>();

            authorities.addAll(roles.convert(jwt));
            authorities.addAll(scopes.convert(jwt));

            return authorities;
        });

        return converter;
    }
}