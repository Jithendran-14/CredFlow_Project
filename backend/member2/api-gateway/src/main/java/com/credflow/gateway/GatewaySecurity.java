package com.credflow.gateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
@Configuration public class GatewaySecurity {
    @Bean ReactiveJwtDecoder decoder(@Value("${credflow.security.issuer:credflow-auth}") String issuer,
                                      @Value("${credflow.security.public-key-location:}") String publicKey, @Value("${credflow.security.audience:credflow-api}") String expectedAudience) {
        var decoder=NimbusReactiveJwtDecoder.withPublicKey(JwtKeys.read(publicKey)).build();
        OAuth2TokenValidator<Jwt> audience=jwt -> jwt.getAudience().contains(expectedAudience) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),audience,jwt -> jwt.getExpiresAt()!=null && jwt.getSubject()!=null && !jwt.getSubject().isBlank()?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }
    @Bean SecurityWebFilterChain chain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(a -> a
            .pathMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/login").permitAll()
            .pathMatchers("/member2/**","/actuator/health","/actuator/health/**").permitAll()
            .pathMatchers("/internal/**","/api/v1/internal/**","/actuator/**").denyAll().anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
    }
}
