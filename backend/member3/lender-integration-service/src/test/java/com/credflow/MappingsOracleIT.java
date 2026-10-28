package com.credflow;

import com.credflow.entity.IntegrationCallback;
import com.credflow.entity.IntegrationDispatch;
import com.credflow.entity.IntegrationTask;
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
		Configuration config = new Configuration();
		config.setProperty("hibernate.connection.driver_class", "oracle.jdbc.OracleDriver");
		config.setProperty("hibernate.connection.pool_size", "1");
		config.setProperty("hibernate.connection.url", required("ORACLE_URL"));
		config.setProperty("hibernate.connection.username", required("INTEGRATION_DB_USER"));
		config.setProperty("hibernate.connection.password", required("INTEGRATION_DB_PASSWORD"));
		config.setProperty("hibernate.hbm2ddl.auto", "validate");
		config.setProperty("hibernate.type.preferred_uuid_jdbc_type", "BINARY");
		config.setProperty("hibernate.type.preferred_boolean_jdbc_type", "NUMERIC");
		config.setProperty("hibernate.jdbc.time_zone", "UTC");
		config.setProperty("hibernate.physical_naming_strategy",
				"org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
		config.addAnnotatedClass(IntegrationDispatch.class);
		config.addAnnotatedClass(IntegrationTask.class);
		config.addAnnotatedClass(IntegrationCallback.class);
		config.addAnnotatedClass(Class.forName("com.credflow.platform.entity.DeliveryRow"));
		try (var factory = config.buildSessionFactory(); var session = factory.openSession()) {
			int answer = session.doReturningWork(connection -> {
				try (var statement = connection.prepareStatement("select 1 from dual");
						var result = statement.executeQuery()) {
					assertTrue(result.next());
					return result.getInt(1);
				}
			});
			assertEquals(1, answer);
			var transaction = session.beginTransaction();
			java.util.UUID fixtureId = java.util.UUID.randomUUID();
			try {
				var row = new com.credflow.entity.IntegrationDispatch();
				row.setSubmissionId(fixtureId);
				row.setApplicationId(java.util.UUID.randomUUID());
				row.setLenderId("oracle-rollback-fixture");
				row.setApplicationRevision(1);
				row.setPayloadHash("0".repeat(64));
				row.setRequestJson("{}");
				session.persist(row);
				session.flush();
				session.clear();
				var stored = session.find(com.credflow.entity.IntegrationDispatch.class, fixtureId);
				assertFalse(stored.isCancellationRequested());
				stored.setCancellationRequested(true);
				session.flush();
				session.clear();
				assertTrue(session.find(com.credflow.entity.IntegrationDispatch.class, fixtureId)
						.isCancellationRequested());
			} finally {
				transaction.rollback();
			}
			session.clear();
			assertNull(session.find(com.credflow.entity.IntegrationDispatch.class, fixtureId));

		}
	}
}
