package com.credflow.simulator.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class VerificationSimulatorTest {
	final Instant time = Instant.parse("2026-10-15T06:00:00Z");

	VerificationSimulator simulator() {
		return new VerificationSimulator(new ObjectMapper(),
				new ClassPathResource("fixtures/verification-scenarios.json"), Clock.fixed(time, ZoneOffset.UTC));
	}

	VerificationSimulator.Request request(String fixture) {
		return new VerificationSimulator.Request(UUID.randomUUID(), UUID.randomUUID(), 1, fixture);
	}

	@ParameterizedTest
	@CsvSource({ "DEMO-VERIFIED-001,VERIFIED", "DEMO-FAILED-001,FAILED", "DEMO-PENDING-001,PENDING",
			"DEMO-ACTION-001,ACTION_REQUIRED" })
	void providesEachScenarioAndPreservesApplicationReferences(String fixture, String status) {
		var request = request(fixture);
		var result = simulator().verify(request);
		assertThat(result.status().name()).isEqualTo(status);
		assertThat(result.applicationId()).isEqualTo(request.applicationId());
		assertThat(result.applicationRevision()).isEqualTo(request.applicationRevision());
		assertThat(result.verificationRequestId()).isEqualTo(request.verificationRequestId());
		assertThat(result.providerReference()).isEqualTo("mock-verification-" + request.verificationRequestId());
		assertThat(result.checkedAt()).isEqualTo(time);
		assertThat(result.message()).isNotBlank();
		if (!status.equals("VERIFIED"))
			assertThat(result.reasonCode()).isNotBlank();
	}

	@Test
	void retryKeepsTheOriginalTimestampAndReference() {
		var simulator = simulator();
		var request = request("DEMO-VERIFIED-001");
		var original = simulator.verify(request);
		assertThat(simulator.verify(request)).isSameAs(original);
	}

	@ParameterizedTest
	@CsvSource({ "revision", "application", "fixture" })
	void changedInputsCannotReuseAnExistingRequest(String change) {
		var simulator = simulator();
		var original = request("DEMO-VERIFIED-001");
		simulator.verify(original);
		var changed = new VerificationSimulator.Request(original.verificationRequestId(),
				change.equals("application") ? UUID.randomUUID() : original.applicationId(),
				change.equals("revision") ? 2 : 1,
				change.equals("fixture") ? "DEMO-FAILED-001" : original.fixtureIdentityId());
		assertThatThrownBy(() -> simulator.verify(changed))
				.isInstanceOf(VerificationSimulator.RequestConflictException.class);
		assertThat(simulator.verify(original).status()).isEqualTo(VerificationSimulator.Status.VERIFIED);
	}

	@Test
	void unknownFixtureIsAnInputErrorNotARejectedIdentity() {
		assertThatThrownBy(() -> simulator().verify(request("DEMO-UNKNOWN-001")))
				.isInstanceOf(VerificationSimulator.UnknownFixtureException.class);
	}

	@Test
	void pendingDoesNotSilentlyBecomeVerifiedOnRetry() {
		var simulator = simulator();
		var request = request("DEMO-PENDING-001");
		assertThat(simulator.verify(request).status()).isEqualTo(VerificationSimulator.Status.PENDING);
		assertThat(simulator.verify(request).status()).isEqualTo(VerificationSimulator.Status.PENDING);
	}

	@Test
	void invalidInputDoesNotGenerateAFakeResult() {
		var simulator = simulator();
		assertThatThrownBy(() -> simulator.verify(
				new VerificationSimulator.Request(UUID.randomUUID(), UUID.randomUUID(), 0, "DEMO-VERIFIED-001")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> simulator
				.verify(new VerificationSimulator.Request(UUID.randomUUID(), null, 1, "DEMO-VERIFIED-001")))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void duplicateFixtureConfigurationFailsAtStartup() {
		String fixture = """
				{"description":"test","fixtures":[
				{"fixtureIdentityId":"DEMO-SAME","status":"VERIFIED","message":"test"},
				{"fixtureIdentityId":"DEMO-SAME","status":"VERIFIED","message":"test"}]}
				""";
		assertThatThrownBy(() -> new VerificationSimulator(new ObjectMapper(),
				new ByteArrayResource(fixture.getBytes(StandardCharsets.UTF_8)), Clock.systemUTC()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
	}

	@Test
	void failureFixtureMustExplainItsResult() {
		String fixture = """
				{"fixtures":[{"fixtureIdentityId":"DEMO-BAD","status":"FAILED","message":"test"}]}
				""";
		assertThatThrownBy(() -> new VerificationSimulator(new ObjectMapper(),
				new ByteArrayResource(fixture.getBytes(StandardCharsets.UTF_8)), Clock.systemUTC()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid");
	}

	@Test
	void parallelRetriesReturnOneStableResult() throws Exception {
		var simulator = simulator();
		var request = request("DEMO-VERIFIED-001");
		var pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<VerificationSimulator.Result>> tasks = new ArrayList<>();
			for (int i = 0; i < 12; i++)
				tasks.add(() -> simulator.verify(request));
			var replies = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);
			var first = replies.getFirst().get();
			for (var reply : replies)
				assertThat(reply.get()).isSameAs(first);
		} finally {
			pool.shutdownNow();
		}
	}
}
