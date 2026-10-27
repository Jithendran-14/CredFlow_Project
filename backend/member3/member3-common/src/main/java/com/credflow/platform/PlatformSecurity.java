package com.credflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class PlatformSecurity {

    @Bean
    JwtDecoder decoder(Environment env) {
    	if (env.matchesProfiles("local-api-test")) {

    	    if (!"127.0.0.1".equals(env.getProperty("server.address"))) {
    	        throw new IllegalStateException(
    	            "Local API testing requires server.address=127.0.0.1"
    	        );
    	    }

    	    return token -> {
    	        Instant now = Instant.now();

    	        var builder = Jwt.withTokenValue(token)
    	            .header("typ", "LOCAL_TEST")
    	            .issuedAt(now)
    	            .expiresAt(now.plusSeconds(3600));

    	        switch (token) {

    	            case "local-test", "local-seller" -> builder
    	                .subject("seller-demo-104")
    	                .claim("sellerId", "SELLER-1001")
    	                .claim("roles", List.of("SELLER"));

    	            case "local-lender-bank-a" -> builder
                    .subject("lender-officer-bank-a")
                    .claim("lenderId", env.getProperty("credflow.lenders.bank-a-id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"))
                    .claim("roles", List.of("LENDER_OFFICER"))
                    .claim("scope", "lender.review");

                case "local-lender-nbfc-b" -> builder
                    .subject("lender-officer-nbfc-b")
                    .claim("lenderId", env.getProperty("credflow.lenders.nbfc-b-id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"))
                    .claim("roles", List.of("LENDER_OFFICER"))
                    .claim("scope", "lender.review");

                case "local-orchestration" -> builder
    	                .subject("orchestration-service")
    	                .claim("client_id", "orchestration-service")
    	                .claim("scope", "integration.write");

    	            case "local-integration" -> builder
    	                .subject("integration-service")
    	                .claim("client_id", "integration-service")
    	                .claim("scope", "lender.lookup lender.submit");

    	            default -> throw new
    	                org.springframework.security.oauth2.jwt.BadJwtException(
    	                    "Unknown local test identity"
    	                );
    	        }

    	        return builder.build();
    	    };
    	}
        String issuer = required(env, "credflow.security.issuer", env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        String audience = required(env, "credflow.security.audience", null);
        String jwks = env.getProperty("credflow.security.jwk-set-uri", "").trim();
        String keyLocation = env.getProperty("credflow.security.public-key-location", "").trim();
        String roleMode = env.getProperty("credflow.security.role-mode", "FLAT");
        if (!Set.of("FLAT", "KEYCLOAK").contains(roleMode)) {
            throw new IllegalStateException("JWT_ROLE_MODE must be FLAT or KEYCLOAK");
        }
        if (jwks.isEmpty() == keyLocation.isEmpty()) {
            throw new IllegalStateException("Configure exactly one JWT_JWKS_URI or JWT_PUBLIC_KEY_LOCATION with the Auth owner's public verification key");
        }
        NimbusJwtDecoder decoder;
        if (!jwks.isEmpty()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            if (!keyLocation.startsWith("file:") && !keyLocation.startsWith("classpath:")) {
                throw new IllegalStateException("JWT_PUBLIC_KEY_LOCATION must use file: or classpath:");
            }
            try (var input = new DefaultResourceLoader().getResource(keyLocation).getInputStream()) {
                RSAPublicKey key = RsaKeyConverters.x509().convert(input);
                if (key == null)
                    throw new IllegalStateException("No RSA public key found");
                if (key.getModulus().bitLength() < 2048) {
                    throw new IllegalStateException("RSA public key must be at least 2048 bits");
                }
                decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            } catch (Exception exception) {
                throw new IllegalStateException("JWT_PUBLIC_KEY_LOCATION must contain a readable X.509 RSA public key PEM", exception);
            }
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null && audiences.contains(audience)), new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank()), new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
        return decoder;
    }

    private static String required(Environment env, String property, String fallback) {
        String value = env.getProperty(property, fallback);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configure " + property + " with the Auth owner's agreed JWT value");
        }
        return value.trim();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, CorsConfigurationSource cors, ObjectMapper mapper, @Qualifier("correlation") OncePerRequestFilter correlation) throws Exception {
        return http.csrf(config -> config.disable()).cors(config -> config.configurationSource(cors)).sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(config -> config.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated()).addFilterBefore(correlation, BearerTokenAuthenticationFilter.class).exceptionHandling(config -> config.authenticationEntryPoint((request, response, failure) -> securityError(response, mapper, 401, "AUTHENTICATION_REQUIRED")).accessDeniedHandler((request, response, failure) -> securityError(response, mapper, 403, "RESOURCE_ACCESS_DENIED"))).oauth2ResourceServer(config -> config.jwt(jwt -> {
        }).authenticationEntryPoint((request, response, failure) -> securityError(response, mapper, 401, "AUTHENTICATION_REQUIRED")).accessDeniedHandler((request, response, failure) -> securityError(response, mapper, 403, "RESOURCE_ACCESS_DENIED"))).build();
    }

    private static void securityError(
        HttpServletResponse response,
        ObjectMapper mapper,
        int status,
        String code
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401)
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        mapper.writeValue(response.getOutputStream(), Map.of("code", code, "message", code, "correlationId", Objects.toString(MDC.get("correlationId"), ""), "fieldErrors", List.of()));
    }

    @Bean
    CorsConfigurationSource cors(Environment env) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(env.getProperty("credflow.frontend-origins", "http://localhost:5173").split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-ID"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    OncePerRequestFilter correlation() {
        return new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                String id = request.getHeader("X-Correlation-ID");
                if (id == null || !id.matches("[A-Za-z0-9._:-]{1,100}")) {
                    id = UUID.randomUUID().toString();
                }
                MDC.put("correlationId", id);
                response.setHeader("X-Correlation-ID", id);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove("correlationId");
                }
            }
        };
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> correlationRegistration(@Qualifier("correlation") OncePerRequestFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}

@RestControllerAdvice
class PlatformErrors {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> missing(Exception exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", List.of());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> forbidden(Exception exception) {
        return error(HttpStatus.FORBIDDEN, "RESOURCE_ACCESS_DENIED", List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException exception) {
        return error(exception.getStatusCode(), Objects.toString(exception.getReason(), "REQUEST_REJECTED"), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream().map(field -> Map.of("field", field.getField(), "message", Objects.toString(field.getDefaultMessage(), "Invalid value"))).toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", fields);
    }

    @ExceptionHandler({ IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.bind.ServletRequestBindingException.class, jakarta.validation.ConstraintViolationException.class })
    ResponseEntity<?> malformed(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", List.of());
    }

    @ExceptionHandler({ org.springframework.web.client.RestClientException.class, org.springframework.dao.DataAccessResourceFailureException.class, org.springframework.transaction.CannotCreateTransactionException.class })
    ResponseEntity<?> unavailable(Exception exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", List.of());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> absentRoute(Exception exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", List.of());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> unsupportedMethod(Exception exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", List.of());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> unsupportedContent(Exception exception) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", List.of());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<?> unacceptableContent(Exception exception) {
        return error(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        org.slf4j.LoggerFactory.getLogger(PlatformErrors.class).error("request_failed error={}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", List.of());
    }

    @ExceptionHandler({ org.springframework.dao.DataIntegrityViolationException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class })
    ResponseEntity<?> conflict(Exception exception) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE_RETRY", List.of());
    }

    private ResponseEntity<?> error(HttpStatusCode status, String code, Object fields) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code, "message", code, "correlationId", Objects.toString(MDC.get("correlationId"), ""), "fieldErrors", fields));
    }
}
