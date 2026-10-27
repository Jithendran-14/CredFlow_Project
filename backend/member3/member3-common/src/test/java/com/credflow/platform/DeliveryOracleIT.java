package com.credflow.platform;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in `-Poracle-it`: real Oracle SQL, transaction rollback and unique-key checks.
 */
class DeliveryOracleIT {

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || value.startsWith("REPLACE_WITH_")) {
            throw new IllegalStateException("Set " + name + " before running the oracle-it profile");
        }
        return value;
    }

    @Test
    void deliveryInsertUniquenessAndRollbackUseOracleInEachServiceSchema() throws Exception {
        String url = required("ORACLE_URL");
        for (String prefix : new String[] { "ORCHESTRATION", "INTEGRATION" }) {
            try (Connection connection = DriverManager.getConnection(url, required(prefix + "_DB_USER"), required(prefix + "_DB_PASSWORD"))) {
                assertTrue(connection.getMetaData().getDatabaseProductName().contains("Oracle"));
                connection.setAutoCommit(false);
                String id = "JOB:" + UUID.randomUUID();
                UUID application = UUID.randomUUID();
                byte[] raw = ByteBuffer.allocate(16).putLong(application.getMostSignificantBits()).putLong(application.getLeastSignificantBits()).array();
                String sql = "insert into CF_M3_DELIVERY " + "(DELIVERY_ID,KIND,APPLICATION_ID,TARGET_NAME,PAYLOAD_JSON,PAYLOAD_HASH," + "POSITION_NO,STATUS,ATTEMPTS,NEXT_ATTEMPT_AT,CREATED_AT,ROW_VERSION) " + "values (?, 'JOB', ?, 'oracle-test', ?, ?, CF_M3_DELIVERY_SEQ.nextval," + "'PENDING',0,?,?,0)";
                try {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, id);
                        statement.setBytes(2, raw);
                        statement.setString(3, "{\"fixture\":\"rollback-only\"}");
                        statement.setString(4, "0".repeat(64));
                        statement.setTimestamp(5, Timestamp.from(Instant.now()));
                        statement.setTimestamp(6, Timestamp.from(Instant.now()));
                        assertEquals(1, statement.executeUpdate());
                        SQLException duplicate = assertThrows(SQLException.class, statement::executeUpdate);
                        assertEquals(1, duplicate.getErrorCode(), "Oracle ORA-00001 enforces the stable delivery ID");
                    }
                    assertEquals(1, count(connection, id));
                } finally {
                    connection.rollback();
                }
                assertEquals(0, count(connection, id), "Rollback must leave no synthetic job behind");
            }
        }
    }

    private int count(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select count(*) from CF_M3_DELIVERY where DELIVERY_ID=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
