package com.credflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityTest {
	@Autowired
	WebTestClient client;
	@MockitoBean
	ReactiveJwtDecoder decoder;

	@Test
	void healthIsPublic() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
	}

	@Test
	void businessRoutesRequireToken() {
		client.post().uri("/api/v1/applications").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void internalEndpointsAreNotPubliclyRouted() {
		client.mutateWith(mockJwt()).get().uri("/internal/applications/123/access").exchange().expectStatus()
				.isForbidden();
	}

	@Test
	void nestedInternalEndpointsAreBlocked() {
		client.mutateWith(mockJwt()).get().uri("/api/v1/internal/eligibility/123").exchange().expectStatus()
				.isForbidden();
	}

	@Test
	void correlationIsSanitized() {
		client.get().uri("/actuator/health").header("X-Correlation-ID", "bad id").exchange().expectHeader()
				.valueMatches("X-Correlation-ID", "[0-9a-f-]{36}");
	}
}
