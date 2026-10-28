package com.credflow.simulator.verification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:verification-api;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
		"credflow.outbox.enabled=false" })
@AutoConfigureMockMvc
class VerificationControllerTest {
	@Autowired
	MockMvc mvc;
	@MockitoBean
	JwtDecoder decoder;
	private static final String PATH = "/internal/v1/verification/checks";

	private String body(String id, String fixture) {
		return "{\"verificationRequestId\":\"" + id + "\",\"applicationId\":\"33333333-3333-4333-8333-333333333333\","
				+ "\"applicationRevision\":1,\"fixtureIdentityId\":\"" + fixture + "\"}";
	}

	private MockHttpServletRequestBuilder authorised(String body) {
		return post(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VERIFICATION_SERVICE")))
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	@ParameterizedTest
	@CsvSource({ "DEMO-VERIFIED-001,VERIFIED", "DEMO-FAILED-001,FAILED", "DEMO-PENDING-001,PENDING",
			"DEMO-ACTION-001,ACTION_REQUIRED" })
	void returnsProviderOutcomes(String fixture, String expected) throws Exception {
		mvc.perform(authorised(body(UUID.randomUUID().toString(), fixture))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value(expected)).andExpect(jsonPath("$.providerReference").exists());
	}

	@Test
	void noTokenDenied() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.content(body(UUID.randomUUID().toString(), "DEMO-VERIFIED-001"))).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@CsvSource({ "SELLER", "ADMIN", "MARKETPLACE" })
	void ordinaryRolesDenied(String role) throws Exception {
		mvc.perform(post(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(UUID.randomUUID().toString(), "DEMO-VERIFIED-001"))).andExpect(status().isForbidden());
	}

	@Test
	void retryStableAndChangedInputConflicts() throws Exception {
		String id = UUID.randomUUID().toString();
		String first = mvc.perform(authorised(body(id, "DEMO-VERIFIED-001"))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retry = mvc.perform(authorised(body(id, "DEMO-VERIFIED-001"))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		assertEquals(first, retry);
		mvc.perform(authorised(body(id, "DEMO-FAILED-001"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERIFICATION_REQUEST_CONFLICT"));
	}

	@Test
	void safeErrorUsesGeneratedCorrelationId() throws Exception {
		var response = mvc.perform(authorised("{")).andExpect(status().isBadRequest()).andReturn().getResponse();
		var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
		assertEquals(response.getHeader("X-Correlation-ID"), json.path("correlationId").asText());
	}

	@Test
	void safeErrorPreservesValidCorrelationId() throws Exception {
		mvc.perform(authorised("{}").header("X-Correlation-ID", "verification-demo-1"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").value("verification-demo-1"));
	}

	@Test
	void additionalFieldsRejected() throws Exception {
		var payload = body(UUID.randomUUID().toString(), "DEMO-VERIFIED-001").replace("}", ",\"unexpected\":true}");
		mvc.perform(authorised(payload)).andExpect(status().isBadRequest());
	}

	@Test
	void fractionalRevisionIsNotSilentlyTruncated() throws Exception {
		var payload = body(UUID.randomUUID().toString(), "DEMO-VERIFIED-001").replace("\"applicationRevision\":1",
				"\"applicationRevision\":1.5");
		mvc.perform(authorised(payload)).andExpect(status().isBadRequest());
	}

	@Test
	void unknownFixtureIsBadRequest() throws Exception {
		mvc.perform(authorised(body(UUID.randomUUID().toString(), "DEMO-UNKNOWN-001")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_VERIFICATION_FIXTURE"));
	}

	@ParameterizedTest
	@CsvSource({ "{}", "{\"applicationRevision\":0}", "{\"verificationRequestId\":\"bad-id\"}" })
	void invalidFieldsAreBadRequest(String payload) throws Exception {
		mvc.perform(authorised(payload)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_REQUEST"));
	}
}
