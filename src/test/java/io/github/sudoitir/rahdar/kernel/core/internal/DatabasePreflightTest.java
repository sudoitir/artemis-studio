package io.github.sudoitir.rahdar.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class DatabasePreflightTest {

    @Test
    void aServerBelow18IsRefusedWithTheVersionNamed() throws Exception {
        try (PostgreSQLContainer<?> old = new PostgreSQLContainer<>("postgres:17-alpine");
                Connection connection = connect(old)) {
            assertThatThrownBy(() -> DatabasePreflight.check(connection, 16))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PostgreSQL 17.")
                    .hasMessageContaining("needs 18 or later");
        }
    }

    @Test
    void aSessionStableServerPassesAndAPoolThatCannotFitIsOnlyAWarning() throws Exception {
        try (PostgreSQLContainer<?> current = new PostgreSQLContainer<>("postgres:18-alpine")
                        .withCommand("postgres", "-c", "max_connections=20");
                Connection connection = connect(current)) {
            assertThat(DatabasePreflight.check(connection, 4)).isEmpty();

            List<String> warnings = DatabasePreflight.check(connection, 50);

            assertThat(warnings)
                    .singleElement()
                    .satisfies(
                            w -> assertThat(w).contains("needs 51 connections").contains("the server allows 17"));
        }
    }

    @Test
    void aServerTimeoutBelowRahdarsIsAWarning() throws Exception {
        try (PostgreSQLContainer<?> current = new PostgreSQLContainer<>("postgres:18-alpine")
                        .withCommand("postgres", "-c", "statement_timeout=5s");
                Connection connection = connect(current)) {
            try (Statement set = connection.createStatement()) {
                set.execute("SET statement_timeout = '30s'");
            }

            assertThat(DatabasePreflight.check(connection, 4))
                    .singleElement()
                    .satisfies(w -> assertThat(w).contains("statement_timeout").contains("5000 ms"));
        }
    }

    @Test
    void aConnectionThatChangesBackendBetweenTransactionsIsRefused() throws Exception {
        AtomicInteger pids = new AtomicInteger();
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("SHOW")) {
                return row(180_000);
            }
            if (sql.contains("has_database_privilege")) {
                return row("app", "rahdar", "public", true, true, true);
            }
            return row(pids.incrementAndGet(), "kept");
        });
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);

        assertThatThrownBy(() -> DatabasePreflight.check(connection, 16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pooler in transaction mode")
                .hasMessageContaining("session mode");
    }

    private static Connection connect(PostgreSQLContainer<?> container) throws SQLException {
        container.start();
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** A one-row result set returning {@code values} by column. */
    private static ResultSet row(Object... values) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            int column = i + 1;
            when(rs.getInt(column)).thenReturn(value instanceof Integer n ? n : 0);
            when(rs.getLong(column)).thenReturn(value instanceof Integer n ? (long) n : 0L);
            when(rs.getBoolean(column)).thenReturn(Boolean.TRUE.equals(value));
            when(rs.getString(column)).thenReturn(String.valueOf(value));
        }
        return rs;
    }
}
