package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The health of every table in Studio's schema and in the plugins' schemas (ADR-0134): size,
 * growth, dead tuples, last vacuum and, for daily-partitioned tables, whether the partitions of
 * the coming days exist. The numbers come from Postgres' statistics, so they are estimates.
 */
@Service
public class StorageHealthService {

    /** Dead tuples above this share of the table, and above {@link #DEAD_ROWS}, is bloat. */
    static final int DEAD_PERCENT = 20;

    static final long DEAD_ROWS = 10_000;

    /** Dead tuples with no vacuum for this long means autovacuum is not keeping up. */
    static final Duration VACUUM_OVERDUE = Duration.ofDays(7);

    /** Today and this many following days must have a daily partition. */
    static final int PARTITION_DAYS_AHEAD = 3;

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String TABLES = """
            SELECT n.nspname,
                   coalesce(p.relname, c.relname) AS name,
                   sum(s.n_live_tup)::bigint,
                   sum(s.n_dead_tup)::bigint,
                   max(greatest(s.last_vacuum, s.last_autovacuum)),
                   sum(pg_total_relation_size(c.oid))::bigint,
                   bool_or(p.oid IS NOT NULL)
            FROM pg_stat_user_tables s
            JOIN pg_class c ON c.oid = s.relid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_inherits i ON i.inhrelid = c.oid
            LEFT JOIN pg_class p ON p.oid = i.inhparent
            WHERE n.nspname = current_schema() OR n.nspname LIKE 'plugin\\_%'
            GROUP BY 1, 2
            ORDER BY 6 DESC""";

    private static final String PARTITIONS = """
            SELECT n.nspname, p.relname, c.relname
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = p.relnamespace
            WHERE n.nspname = current_schema() OR n.nspname LIKE 'plugin\\_%'""";

    /**
     * One table, its partitions folded in.
     *
     * @param growthBytes size now minus the size sampled a week ago; {@code null} without a sample that old
     * @param missingPartitions the coming days without a daily partition; empty for a table not partitioned by day
     * @param problems why the table is unhealthy; empty when it is healthy
     */
    public record TableHealth(
            String schema,
            String name,
            long rows,
            long deadRows,
            int deadPercent,
            Instant lastVacuum,
            long bytes,
            Long growthBytes,
            boolean partitioned,
            List<LocalDate> missingPartitions,
            List<String> problems) {

        public boolean healthy() {
            return problems.isEmpty();
        }
    }

    private record Row(
            String schema, String name, long rows, long dead, Instant vacuum, long bytes, boolean partitioned) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    StorageHealthService(JdbcTemplate jdbc, Clock clock, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.lifecycle.DataPermissions).DATA_READ)")
    public List<TableHealth> tables() {
        return health();
    }

    /** Every table's health, for callers already inside the system (alert evaluation), without a permission check. */
    public List<TableHealth> health() {
        Instant now = clock.instant();
        Map<String, Long> weekAgo = sizesAt(now.minus(Duration.ofDays(7)));
        Map<String, Set<String>> partitions = partitions();
        List<TableHealth> out = new ArrayList<>();
        for (Row row : rows()) {
            String key = row.schema() + "." + row.name();
            int deadPercent = row.rows() + row.dead() == 0 ? 0 : (int) (row.dead() * 100 / (row.rows() + row.dead()));
            List<LocalDate> missing = missingPartitions(partitions.get(key), row.name(), now);
            List<String> problems = new ArrayList<>();
            if (deadPercent > DEAD_PERCENT && row.dead() > DEAD_ROWS) {
                problems.add(deadPercent + "% of its rows are dead");
            }
            if (row.dead() > 0
                    && (row.vacuum() == null || row.vacuum().isBefore(now.minus(VACUUM_OVERDUE)))
                    && row.dead() > DEAD_ROWS) {
                problems.add("not vacuumed for over " + VACUUM_OVERDUE.toDays() + " days");
            }
            if (!missing.isEmpty()) {
                problems.add("no partition for " + missing.getFirst());
            }
            Long before = weekAgo.get(key);
            out.add(new TableHealth(
                    row.schema(),
                    row.name(),
                    row.rows(),
                    row.dead(),
                    deadPercent,
                    row.vacuum(),
                    row.bytes(),
                    before == null ? null : row.bytes() - before,
                    row.partitioned(),
                    missing,
                    List.copyOf(problems)));
        }
        return out;
    }

    /** Records every table's size for growth, then lets the installation alerts re-evaluate. */
    public void sample() {
        Instant sampledAt = clock.instant();
        Timestamp at = Timestamp.from(sampledAt);
        List<Object[]> batch = rows().stream()
                .map(r -> new Object[] {at, r.bytes(), r.schema(), r.name()})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO storage_sample (sampled_at, bytes, schema_name, table_name) VALUES (?, ?, ?, ?)", batch);
        events.publishEvent(new StorageSampled(sampledAt));
    }

    private List<Row> rows() {
        return jdbc.query(
                TABLES,
                (rs, i) -> new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getLong(6),
                        rs.getBoolean(7)));
    }

    /** The latest sampled size of each table at or before {@code at}. */
    private Map<String, Long> sizesAt(Instant at) {
        Map<String, Long> sizes = new HashMap<>();
        jdbc.query(
                """
                SELECT DISTINCT ON (schema_name, table_name) schema_name, table_name, bytes
                FROM storage_sample WHERE sampled_at <= ?
                ORDER BY schema_name, table_name, sampled_at DESC""",
                rs -> {
                    sizes.put(rs.getString(1) + "." + rs.getString(2), rs.getLong(3));
                },
                Timestamp.from(at));
        return sizes;
    }

    /** Partition names per partitioned table ({@code schema.parent}). */
    private Map<String, Set<String>> partitions() {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.query(PARTITIONS, rs -> {
            out.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new HashSet<>())
                    .add(rs.getString(3));
        });
        return out;
    }

    /**
     * The coming days with no {@code <table>_yyyyMMdd} partition. Only a table that already has
     * such daily partitions is checked, since that naming is how Studio's daily partitions are made.
     */
    static List<LocalDate> missingPartitions(Set<String> children, String table, Instant now) {
        if (children == null || children.stream().noneMatch(c -> c.matches(table + "_\\d{8}"))) {
            return List.of();
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<LocalDate> missing = new ArrayList<>();
        for (int d = 0; d <= PARTITION_DAYS_AHEAD; d++) {
            LocalDate day = today.plusDays(d);
            if (!children.contains(table + "_" + DAY.format(day))) {
                missing.add(day);
            }
        }
        return List.copyOf(missing);
    }
}
