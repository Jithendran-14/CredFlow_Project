package com.credflow.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:audit;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none", "credflow.outbox.enabled=false",
		"credflow.audit.consumer-enabled=false" })
@AutoConfigureMockMvc
class FoundationSecurityTest {
	@Autowired
	MockMvc mvc;
	@MockitoBean
	JwtDecoder decoder;

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void apiRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/system/audit-service/info")).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedCallerCanCheckService() throws Exception {
		mvc.perform(get("/api/v1/system/audit-service/info").with(jwt())).andExpect(status().isOk())
				.andExpect(jsonPath("$.service").value("audit-service"));
	}

	@Test
	void metricsRequireAdmin() throws Exception {
		mvc.perform(get("/actuator/metrics").with(jwt())).andExpect(status().isForbidden());
	}

	@Test
	void correlationHeaderIsPreserved() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Correlation-ID", "demo-104"))
				.andExpect(header().string("X-Correlation-ID", "demo-104"));
	}
}
