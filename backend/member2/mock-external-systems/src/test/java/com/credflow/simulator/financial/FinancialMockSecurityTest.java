package com.credflow.simulator.financial;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:financial-security;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none" })
@AutoConfigureMockMvc
class FinancialMockSecurityTest {
	@Autowired
	MockMvc mvc;
	@MockitoBean
	JwtDecoder decoder;
	@MockitoBean
	FinancialMockService service;

	@Test
	void anonymousCannotFetchFixture() throws Exception {
		mvc.perform(post("/internal/v1/financial-data/retrievals").contentType("application/json").content("{}")
				.header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}

	@Test
	void sellerCannotFetchFixture() throws Exception {
		mvc.perform(post("/internal/v1/financial-data/retrievals")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SELLER"))).contentType("application/json")
				.content("{}").header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void correctRoleOnWrongClientIsDenied() throws Exception {
		mvc.perform(post("/internal/v1/financial-data/retrievals")
				.with(jwt().jwt(j -> j.claim("client_id", "other-service"))
						.authorities(new SimpleGrantedAuthority("ROLE_VERIFICATION_SERVICE")))
				.contentType("application/json").content("{}").header("Idempotency-Key", UUID.randomUUID()))
				.andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}
}
