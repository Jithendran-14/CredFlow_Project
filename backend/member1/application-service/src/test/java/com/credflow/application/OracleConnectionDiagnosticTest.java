
package com.credflow.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class OracleConnectionDiagnosticTest {

    @Test
    void shouldConnectToOracleTestSchema() throws Exception {

        String password =
                System.getenv("CREDFLOW_TEST_DB_PASSWORD");

        assertNotNull(
                password,
                "CREDFLOW_TEST_DB_PASSWORD is not available to JUnit");

        String url =
                "jdbc:oracle:thin:@//localhost:1521/FREEPDB1";

        try (Connection connection = DriverManager.getConnection(
                url,
                "CF_APPLICATION_TEST",
                password);

             Statement statement = connection.createStatement();

             ResultSet resultSet = statement.executeQuery(
                     "SELECT USER FROM DUAL")) {

            resultSet.next();

            String connectedUser = resultSet.getString(1);

            assertEquals(
                    "CF_APPLICATION_TEST",
                    connectedUser,
                    "Connected to the wrong Oracle schema");
        }
    }
}
