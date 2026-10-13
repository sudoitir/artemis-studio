package io.github.sudoitir.artemisstudio.kernel.lifecycle.partition;

import io.github.sudoitir.artemisstudio.kernel.core.StatementTimeout;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the catalog says about a partitioned table's children, and the one way to run a statement that
 * cannot sit in a transaction ({@code VACUUM}, {@code DETACH ... CONCURRENTLY}) with a long timeout.
 * Table names are never qualified: they resolve in the current schema.
 */
final class PartitionCatalog {

    /** A partition of a spec's table; {@code start} is {@code null} when its name is not one Studio made. */
    record Child(String name, LocalDate start, boolean detachPending, boolean frozen) {}

    static final String FROZEN = "frozen";

    private static final String CHILDREN = """
            SELECT c.relname, i.inhdetachpending, obj_description(c.oid, 'pg_class') = '%s'
              FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
             WHERE i.inhparent = to_regclass(?)
             ORDER BY c.relname""".formatted(FROZEN);

    private PartitionCatalog() {}

    static List<Child> children(JdbcTemplate jdbc, PartitionSpec spec) {
        return jdbc.query(
                CHILDREN,
                (rs, i) -> new Child(
                        rs.getString(1),
                        spec.grain().start(spec.table(), rs.getString(1)),
                        rs.getBoolean(2),
                        rs.getBoolean(3)),
                spec.table());
    }

    static boolean attached(JdbcTemplate jdbc, PartitionSpec spec, String name) {
        return children(jdbc, spec).stream().anyMatch(c -> c.name().equals(name));
    }

    /** Planner's row estimate of one partition, never negative. */
    static long rows(JdbcTemplate jdbc, String name) {
        Long rows = jdbc.queryForObject(
                "SELECT greatest(reltuples, 0)::bigint FROM pg_class WHERE oid = to_regclass(?)", Long.class, name);
        return rows == null ? 0 : rows;
    }

    /**
     * Runs {@code statements}, each as its own auto-committed statement, on one connection with the long
     * statement timeout, and puts the connection's own timeout back afterwards.
     */
    static void outsideTransaction(JdbcTemplate jdbc, String... statements) {
        jdbc.execute((java.sql.Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                String previous;
                try (var rs = statement.executeQuery("SHOW statement_timeout")) {
                    rs.next();
                    previous = rs.getString(1);
                }
                statement.execute("SET statement_timeout = " + StatementTimeout.LONG.toMillis());
                try {
                    for (String sql : statements) {
                        statement.execute(sql);
                    }
                } finally {
                    statement.execute("SET statement_timeout = '" + previous + "'");
                }
            } catch (SQLException e) {
                throw jdbc.getExceptionTranslator().translate("partition maintenance", null, e);
            }
            return null;
        });
    }
}
