package com.credflow;

import com.credflow.entity.SimCallback;
import com.credflow.entity.SimCase;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in live Oracle mapping/schema check; no DDL and no H2.
 */
class MappingsOracleIT {

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank() || value.startsWith("REPLACE_WITH_"))
			throw new IllegalStateException("Set " + name + " before running the oracle-it profile");
		return value;
	}

	@Test
	void actualOracleSchemaMatchesEveryServiceEntity() throws Exception {
		validate("BANK_A");
	}

	private void validate(String prefix) throws Exception {
		Configuration config = new Configuration();
		config.setProperty("hibernate.connection.driver_class", "oracle.jdbc.OracleDriver");
		config.setProperty("hibernate.connection.pool_size", "1");
		String bankUrl = System.getenv(prefix + "_ORACLE_URL");
		config.setProperty("hibernate.connection.url",
				bankUrl == null || bankUrl.isBlank() ? required("ORACLE_URL") : bankUrl);
		config.setProperty("hibernate.connection.username", required(prefix + "_DB_USER"));
		config.setProperty("hibernate.connection.password", required(prefix + "_DB_PASSWORD"));
		config.setProperty("hibernate.hbm2ddl.auto", "validate");
		config.setProperty("hibernate.type.preferred_uuid_jdbc_type", "BINARY");
		config.setProperty("hibernate.type.preferred_boolean_jdbc_type", "NUMERIC");
		config.setProperty("hibernate.jdbc.time_zone", "UTC");
		config.setProperty("hibernate.physical_naming_strategy",
				"org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
		config.addAnnotatedClass(SimCase.class);
		config.addAnnotatedClass(SimCallback.class);
		try (var factory = config.buildSessionFactory(); var session = factory.openSession()) {
			int answer = session.doReturningWork(connection -> {
				try (var statement = connection.prepareStatement("select 1 from dual");
						var result = statement.executeQuery()) {
					assertTrue(result.next());
					return result.getInt(1);
				}
			});
			assertEquals(1, answer);
		}
	}
}
