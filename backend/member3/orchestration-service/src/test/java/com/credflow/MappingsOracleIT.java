package com.credflow;

import com.credflow.entity.IdempotencyRow;
import com.credflow.entity.InformationRow;
import com.credflow.entity.OfferRow;
import com.credflow.entity.SubmissionRow;
import com.credflow.entity.WorkflowRow;
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
        config.setProperty("hibernate.connection.username", required("ORCHESTRATION_DB_USER"));
        config.setProperty("hibernate.connection.password", required("ORCHESTRATION_DB_PASSWORD"));
        config.setProperty("hibernate.hbm2ddl.auto", "validate");
        config.setProperty("hibernate.type.preferred_uuid_jdbc_type", "BINARY");
        config.setProperty("hibernate.type.preferred_boolean_jdbc_type", "NUMERIC");
        config.setProperty("hibernate.jdbc.time_zone", "UTC");
        config.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        config.addAnnotatedClass(WorkflowRow.class);
        config.addAnnotatedClass(SubmissionRow.class);
        config.addAnnotatedClass(OfferRow.class);
        config.addAnnotatedClass(InformationRow.class);
        config.addAnnotatedClass(IdempotencyRow.class);
        config.addAnnotatedClass(Class.forName("com.credflow.platform.entity.DeliveryRow"));
        try (var factory = config.buildSessionFactory();
            var session = factory.openSession()) {
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
                var row = new WorkflowRow();row.setId(fixtureId);row.setAmount(new java.math.BigDecimal("1.00"));row.setCurrency("INR");row.setRevision(1);row.setStatus("DRAFT");row.setHealth("OK");
                session.persist(row);session.flush();session.clear();
                var stored=session.find(WorkflowRow.class,fixtureId);assertFalse(stored.isConsentGranted());stored.setConsentGranted(true);session.flush();session.clear();assertTrue(session.find(WorkflowRow.class,fixtureId).isConsentGranted());
            } finally { transaction.rollback(); }
            session.clear();assertNull(session.find(WorkflowRow.class,fixtureId));

        }
    }
}
