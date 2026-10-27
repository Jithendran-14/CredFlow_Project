
package com.credflow.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

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

                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()

                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/applications"
                ).hasRole("MARKETPLACE")

                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/applications", "/api/v1/applications/*"
                ).hasAnyRole(
                    "SELLER",
                    "MARKETPLACE",
                    "ADMIN"
                )
                .requestMatchers(
                	    HttpMethod.GET,
                	    "/internal/v1/applications/*/context"
                	).hasAnyRole(
                	    "ELIGIBILITY_SERVICE",
                	    "VERIFICATION_SERVICE"
                	)
                .requestMatchers(
                	    HttpMethod.POST,
                	    "/internal/v1/applications/*/ownership-check"
                	).hasAnyRole(
                	    "ELIGIBILITY_SERVICE",
                	    "VERIFICATION_SERVICE"
                	)
                .requestMatchers(HttpMethod.POST,"/api/v1/applications/*/corrections").hasRole("SELLER")
                .requestMatchers(HttpMethod.GET,"/api/v1/applications/*/corrections").hasAnyRole("SELLER","MARKETPLACE","ADMIN")
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

    private JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter authoritiesConverter =
            new JwtGrantedAuthoritiesConverter();

        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter =
            new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(
            authoritiesConverter
        );

        return converter;
    }
}
