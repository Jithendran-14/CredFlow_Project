
package com.credflow.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTransactionIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldConnectOnlyToApplicationTestSchema() throws Exception {

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // Confirm the Oracle user and PDB.
            try (ResultSet resultSet = statement.executeQuery(
                    """
                    SELECT
                        SYS_CONTEXT('USERENV', 'CURRENT_USER'),
                        SYS_CONTEXT('USERENV', 'CON_NAME')
                    FROM DUAL
                    """)) {

                assertTrue(resultSet.next());

                assertEquals(
                        "CF_APPLICATION_TEST",
                        resultSet.getString(1),
                        "Integration test connected to the wrong Oracle schema!");

                assertEquals(
                        "FREEPDB1",
                        resultSet.getString(2),
                        "Integration test connected to the wrong Oracle PDB!");
            }

            // Confirm all four expected tables exist.
            try (ResultSet resultSet = statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM USER_TABLES
                    WHERE TABLE_NAME IN (
                        'CF_APPLICATIONS',
                        'CF_IDEMPOTENCY_KEYS',
                        'CF_SELLER_MAPPINGS',
                        'CF_OUTBOX_EVENTS'
                    )
                    """)) {

                assertTrue(resultSet.next());

                assertEquals(
                        4,
                        resultSet.getInt(1),
                        "Expected four tables in the test schema");
            }
        }
    }
}
