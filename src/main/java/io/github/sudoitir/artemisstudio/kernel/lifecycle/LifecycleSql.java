package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.core.SqlIdentifier;
import io.github.sudoitir.artemisstudio.kernel.core.StatementTimeout;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The statements a row store needs (ADR-0134). {@code predicate} is a SQL condition with exactly
 * one {@code ?}, bound to the cutoff, such as {@code "received_at < ?"}. Each call runs in its own
 * auto-committed statement, which is what makes a batch its own short transaction. A plugin's
 * store uses it with a {@code JdbcTemplate} over its own data source, so an unqualified table name
 * resolves in the plugin's schema.
 */
@PluginApi
public final class LifecycleSql {

    private static final String USAGE = """
            SELECT coalesce(sum(greatest(c.reltuples, 0)), 0)::bigint,
                   coalesce(sum(pg_total_relation_size(t.relid)), 0)::bigint
            FROM pg_partition_tree(?::regclass) t JOIN pg_class c ON c.oid = t.relid
            WHERE t.isleaf""";

    private static final String WHERE = " WHERE ";

    private LifecycleSql() {}

    /**
     * Deletes at most {@code limit} rows matching {@code predicate}. By {@code ctid}, so the
     * batch is bounded without an ordering column and without holding a lock on the rest. Not for
     * a partitioned table, where a {@code ctid} is only unique within one partition.
     */
    public static long deleteBatch(JdbcTemplate jdbc, String table, String predicate, Instant cutoff, int limit) {
        String name = SqlIdentifier.quote(table);
        return jdbc.update(
                "DELETE FROM " + name + " WHERE ctid = ANY(ARRAY(SELECT ctid FROM " + name + WHERE + predicate
                        + " LIMIT ?))",
                Timestamp.from(cutoff),
                limit);
    }

    /**
     * Deletes the child rows of the parents {@code parentPredicate} selects, in batches of at most
     * {@code limit}, until none are left. Run before deleting the parents, so that no single statement
     * cascades through an unbounded number of children: {@code ON DELETE CASCADE} deletes them all inside
     * the parent's one statement. {@code parentPredicate} has exactly one {@code ?}, as in {@link #deleteBatch}.
     */
    public static void deleteChildren(
            JdbcTemplate jdbc,
            String child,
            String foreignKey,
            String parent,
            String parentPredicate,
            Instant cutoff,
            int limit) {
        String childName = SqlIdentifier.quote(child);
        String sql = "DELETE FROM " + childName + " WHERE ctid = ANY(ARRAY(SELECT ctid FROM " + childName + WHERE
                + SqlIdentifier.quote(foreignKey) + " IN (SELECT id FROM " + SqlIdentifier.quote(parent) + WHERE
                + parentPredicate + ") LIMIT ?))";
        while (!Thread.currentThread().isInterrupted() && jdbc.update(sql, Timestamp.from(cutoff), limit) > 0) {
            // each batch is its own transaction: the next one starts when the last has committed
        }
    }

    /** Counts the rows {@link #deleteBatch} would remove, and prices them at the table's average row size. */
    public static PurgeEstimate estimate(JdbcTemplate jdbc, String table, String predicate, Instant cutoff) {
        Long rows = StatementTimeout.inLongTransaction(
                jdbc,
                () -> jdbc.queryForObject(
                        "SELECT count(*) FROM " + SqlIdentifier.quote(table) + WHERE + predicate,
                        Long.class,
                        Timestamp.from(cutoff)));
        StoreUsage usage = usage(jdbc, java.util.List.of(table));
        long bytesPerRow = usage.rows() > 0 ? usage.bytes() / usage.rows() : 0;
        long n = rows == null ? 0 : rows;
        return new PurgeEstimate(n, n * bytesPerRow);
    }

    /**
     * Rows (planner estimates, so free to read) and bytes of {@code tables}, summed over every
     * partition of a partitioned one.
     */
    public static StoreUsage usage(JdbcTemplate jdbc, Collection<String> tables) {
        long rows = 0;
        long bytes = 0;
        for (String table : tables) {
            StoreUsage one = jdbc.queryForObject(USAGE, (rs, i) -> new StoreUsage(rs.getLong(1), rs.getLong(2)), table);
            rows += one.rows();
            bytes += one.bytes();
        }
        return new StoreUsage(rows, bytes);
    }
}
