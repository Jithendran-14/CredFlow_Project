
package com.credflow.auth.config;

import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

		http.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/info").permitAll()

						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/forgot-password",
								"/api/v1/auth/reset-password", "/api/v1/auth/register-seller", "/oauth2/token")
						.permitAll()

						.requestMatchers(HttpMethod.GET, "/api/v1/auth/marketplaces").permitAll()
						.requestMatchers("/api/v1/seller/**").hasRole("SELLER")
						.requestMatchers("/api/v1/marketplace/**").hasRole("MARKETPLACE")
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

						.anyRequest().authenticated())
				.oauth2ResourceServer(
						oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

		return http.build();
	}

	@Bean
	public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {

		return jwt -> {
			List<String> roles = jwt.getClaimAsStringList("roles");

			Collection<SimpleGrantedAuthority> authorities = roles == null ? List.of()
					: roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();

			return new JwtAuthenticationToken(jwt, authorities);
		};
	}
}
