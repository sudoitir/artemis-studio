package io.github.sudoitir.artemisstudio.kernel.core.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

/**
 * The check of the database that runs before Liquibase (ADR-0199). Studio may be pointed at a managed
 * PostgreSQL it does not administer, so it looks before it creates anything, and fails the boot with a
 * message that names the fix when it cannot run there:
 *
 * <ul>
 *   <li>the server is older than 18;
 *   <li>the role lacks {@code CREATE} on the database (plugins get a schema each) or {@code USAGE} and
 *       {@code CREATE} on the current schema;
 *   <li>the connection is not session-stable: two transactions on one connection must reach the same
 *       backend and keep a session setting, which transaction-mode pooling breaks, and Studio relies on
 *       session advisory locks, {@code LISTEN} and per-connection limits.
 * </ul>
 *
 * It only warns, and reports the warnings through the {@code database} health contributor, when the
 * server's connection limit cannot hold one replica's pool and listening connection, or when the
 * server's own statement or idle-in-transaction timeout is lower than the one Studio sets.
 */
@Slf4j
class DatabasePreflight implements InitializingBean {

    static final int MINIMUM_VERSION = 180_000;

    private static final String PROBE = "studio.preflight";
    private static final int PROBES = 3;

    private final DataSource dataSource;
    private final AtomicReference<List<String>> warnings = new AtomicReference<>(List.of());

    DatabasePreflight(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** What the check found worth an operator's attention; empty when there is nothing. */
    List<String> warnings() {
        return warnings.get();
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        int pool;
        try {
            pool = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        } catch (SQLException e) {
            throw new IllegalStateException("The primary datasource is not a Hikari pool", e);
        }
        try (Connection connection = dataSource.getConnection()) {
            warnings.set(check(connection, pool));
        }
        warnings.get().forEach(warning -> log.warn("Database: {}", warning));
    }

    /** Runs every check on {@code connection}; throws on a problem and returns the warnings otherwise. */
    static List<String> check(Connection connection, int poolSize) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            int version = serverVersion(statement);
            if (version < MINIMUM_VERSION) {
                throw refused(List.of(
                        "The server is PostgreSQL %d.%d, and Studio needs 18 or later (it uses uuidv7()). Upgrade the server, or point Studio at a PostgreSQL 18 database."
                                .formatted(version / 10_000, version % 10_000)));
            }
            List<String> problems = new ArrayList<>();
            privileges(statement, problems);
            sessionStability(statement, problems);
            if (!problems.isEmpty()) {
                throw refused(problems);
            }
            return warnings(statement, poolSize);
        }
    }

    private static IllegalStateException refused(List<String> problems) {
        return new IllegalStateException("Studio cannot run on this database:\n - " + String.join("\n - ", problems));
    }

    private static int serverVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SHOW server_version_num")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void privileges(Statement statement, List<String> problems) throws SQLException {
        try (ResultSet rs = statement.executeQuery("""
                SELECT current_database(), current_user, current_schema(),
                       has_database_privilege(current_database(), 'CREATE'),
                       current_schema() IS NOT NULL AND has_schema_privilege(current_schema(), 'USAGE'),
                       current_schema() IS NOT NULL AND has_schema_privilege(current_schema(), 'CREATE')
                """)) {
            rs.next();
            String database = rs.getString(1);
            String user = rs.getString(2);
            String schema = rs.getString(3);
            if (!rs.getBoolean(4)) {
                problems.add(
                        "The role %s cannot create schemas in %s, which each plugin needs for its tables. Run GRANT CREATE ON DATABASE %s TO %s."
                                .formatted(user, database, database, user));
            }
            if (schema == null) {
                problems.add(
                        "The schema named by currentSchema in the JDBC URL does not exist. Create it with CREATE SCHEMA <name> AUTHORIZATION %s, or remove currentSchema."
                                .formatted(user));
            } else if (!rs.getBoolean(5) || !rs.getBoolean(6)) {
                problems.add(
                        "The role %s needs USAGE and CREATE on the schema %s. Run GRANT USAGE, CREATE ON SCHEMA %s TO %s, or make the role its owner."
                                .formatted(user, schema, schema, user));
            }
        }
    }

    /** Two transactions on one connection must see one backend and one session setting. */
    private static void sessionStability(Statement statement, List<String> problems) throws SQLException {
        try {
            for (int i = 0; i < PROBES; i++) {
                long first;
                try (ResultSet rs =
                        statement.executeQuery("SELECT pg_backend_pid(), set_config('" + PROBE + "', 'kept', false)")) {
                    rs.next();
                    first = rs.getLong(1);
                }
                try (ResultSet rs =
                        statement.executeQuery("SELECT pg_backend_pid(), current_setting('" + PROBE + "', true)")) {
                    rs.next();
                    if (rs.getLong(1) != first || !"kept".equals(rs.getString(2))) {
                        problems.add(
                                "The connection does not keep its session between transactions, as with a pooler in transaction mode (PgBouncer, RDS Proxy). Studio needs session state for advisory locks, LISTEN and its per-connection limits. Connect directly or through a pooler in session mode.");
                        return;
                    }
                }
            }
        } finally {
            try {
                statement.execute("RESET " + PROBE);
            } catch (SQLException _) {
                // The connection is going back to the pool; nothing relies on the setting.
            }
        }
    }

    private static List<String> warnings(Statement statement, int poolSize) throws SQLException {
        Map<String, long[]> settings = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("""
                SELECT name, setting::bigint, reset_val::bigint FROM pg_settings
                WHERE name IN ('max_connections', 'superuser_reserved_connections', 'reserved_connections',
                               'statement_timeout', 'idle_in_transaction_session_timeout')
                """)) {
            while (rs.next()) {
                settings.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }
        }
        List<String> warnings = new ArrayList<>();
        long reserved = value(settings, "superuser_reserved_connections") + value(settings, "reserved_connections");
        long available = value(settings, "max_connections") - reserved;
        long needed = poolSize + 1L;
        if (needed > available) {
            warnings.add(
                    "One Studio replica needs %d connections (a pool of %d and the one it listens on), and the server allows %d after reserving %d. Plugins add 3 each and every replica adds the same again. Raise max_connections or lower ARTEMIS_STUDIO_DB_POOL_SIZE."
                            .formatted(needed, poolSize, available, reserved));
        }
        for (String timeout : List.of("statement_timeout", "idle_in_transaction_session_timeout")) {
            long[] values = settings.get(timeout);
            if (values != null && values[1] != 0 && values[1] < values[0]) {
                warnings.add(
                        "The server's default %s is %d ms, below the %d ms Studio sets on each connection. Studio's setting wins, unless a proxy between them resets session settings."
                                .formatted(timeout, values[1], values[0]));
            }
        }
        return warnings;
    }

    private static long value(Map<String, long[]> settings, String name) {
        long[] values = settings.get(name);
        return values == null ? 0 : values[0];
    }
}
