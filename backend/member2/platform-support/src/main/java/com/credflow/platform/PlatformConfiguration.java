package com.credflow.platform;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
@Configuration @EnableMethodSecurity
public class PlatformConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${credflow.security.issuer:credflow-auth}") String issuer,
                                @Value("${credflow.security.public-key-location:}") String publicKey,
                                @Value("${credflow.security.audience:credflow-api}") String audience) {
        var decoder=NimbusJwtDecoder.withPublicKey(JwtKeys.read(publicKey)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),new AudienceValidator(audience), jwt ->
            jwt.getExpiresAt()!=null && jwt.getSubject()!=null && !jwt.getSubject().isBlank()
                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"))));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(PlatformConfiguration::roles);
        return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET,"/member2/**","/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN").anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
    static Collection<org.springframework.security.core.GrantedAuthority> roles(Jwt jwt) {
        Object access=jwt.getClaims().get("roles");
        if(!(access instanceof Collection<?> roles))return List.of();
        return roles.stream().filter(String.class::isInstance).filter(r->!((String)r).isBlank())
            .<org.springframework.security.core.GrantedAuthority>map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList();
    }
    @Bean FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        var registration=new FilterRegistrationBean<>(new CorrelationFilter()); registration.setOrder(Ordered.HIGHEST_PRECEDENCE); return registration;
    }
    @RestController public static class FoundationController {
        private final String name;
        public FoundationController(@Value("${spring.application.name}") String name) { this.name=name; }
        @GetMapping("/api/v1/system/{service}/info") public Map<String,String> info(@PathVariable String service) {
            if(!name.equals(service)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
            return Map.of("service",name,"stage","FOUNDATION");
        }
    }
}
