package com.credflow.platform;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureConfigurationTest {
	private final Path root = Path.of("..").toAbsolutePath().normalize();

	@Test
	void allYamlAndJsonConfigurationParses() throws Exception {
		try (var files = Files.walk(root)) {
			for (Path file : files.filter(Files::isRegularFile).filter(p -> !p.toString().contains("target"))
					.toList()) {
				if (file.toString().endsWith(".yml"))
					new Yaml().load(Files.readString(file));
				if (file.toString().endsWith(".json"))
					new ObjectMapper().readTree(Files.readString(file));
			}
		}
	}

	@Test
	void handoffContainsEveryBuildModuleAndManualInstaller() throws Exception {
		String pom = Files.readString(root.resolve("pom.xml"));
		for (String module : List.of("platform-support", "eligibility-service", "audit-service",
				"mock-external-systems", "api-gateway")) {
			assertThat(pom).contains("<module>" + module + "</module>");
			assertThat(Files.exists(root.resolve(module + "/pom.xml"))).isTrue();
		}
		for (String schema : List.of("eligibility", "audit", "mock-financial"))
			assertThat(Files.exists(root.resolve("database/manual/" + schema + "/install-fresh.sql"))).isTrue();
	}

	@Test
	void activeSetupUsesCustomJwtAndManualSchema() throws Exception {
		for (String service : List.of("eligibility-service", "audit-service")) {
			assertThat(Files.readString(root.resolve("" + service + "/pom.xml"))).doesNotContain("org.flywaydb");
			assertThat(Files.readString(root.resolve("" + service + "/src/main/resources/application.yml")))
					.contains("ddl-auto: validate", "mode: never", "JWT_PUBLIC_KEY_PATH",
							"show-details: when-authorized")
					.doesNotContain("flyway:");
		}
	}
}
