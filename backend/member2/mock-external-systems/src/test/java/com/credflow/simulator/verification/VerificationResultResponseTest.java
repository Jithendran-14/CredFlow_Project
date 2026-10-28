package com.credflow.simulator.verification;

import com.credflow.simulator.verification.api.VerificationResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class VerificationResultResponseTest {
	@ParameterizedTest
	@CsvSource({ "DEMO-VERIFIED-001,VERIFIED", "DEMO-FAILED-001,FAILED", "DEMO-PENDING-001,PENDING",
			"DEMO-ACTION-001,ACTION_REQUIRED" })
	void producesTheDraftShapeForEveryScenario(String fixture, String expected) {
		var json = new ObjectMapper();
		var provider = new VerificationSimulator(json, new ClassPathResource("fixtures/verification-scenarios.json"),
				Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC));
		var request = new VerificationSimulator.Request(UUID.randomUUID(), UUID.randomUUID(), 1, fixture);
		var node = json.valueToTree(VerificationResultResponse.from(provider.verify(request)));
		assertThat(node.path("status").asText()).isEqualTo(expected);
		assertThat(node.path("applicationId").asText()).isEqualTo(request.applicationId().toString());
		assertThat(node.path("verificationRequestId").asText()).isEqualTo(request.verificationRequestId().toString());
		assertThat(node.path("checkedAt").asText()).isEqualTo("2026-10-15T09:00:00Z");
		assertThat(node.has("verificationId")).isFalse();
		if (expected.equals("VERIFIED"))
			assertThat(node.has("reasonCode")).isFalse();
		else
			assertThat(node.path("reasonCode").asText()).isNotBlank();
	}
}
