package io.github.sudoitir.rahdar.support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Partitions for rows that tests write in the past. Rahdar creates only the current period and the ones
 * ahead, since a real write is never dated before today (ADR-0193), and a purge test drops the old ones.
 * A test that inserts a row dated {@code ago} days back needs its partition, which this creates the same
 * way the maintainer does: as a plain table, then attached.
 */
public final class TestPartitions {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd '00:00:00+00'");
    private static final List<String> DAILY = List.of("metric_sample", "message_index", "broker_event", "rr_event");

    /** How far back the shared base class keeps daily partitions in place. */
    private static final int DAYS_BACK = 14;

    private static final int MONTHS_BACK = 3;

    private TestPartitions() {}

    /** Every daily table back {@value #DAYS_BACK} days, and {@code audit_event} back three months. */
    public static void ensureBack(JdbcTemplate jdbc) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> wanted = new ArrayList<>();
        for (String table : DAILY) {
            for (int i = 0; i <= DAYS_BACK; i++) {
                wanted.add(table + "_p" + DAY.format(today.minusDays(i)));
            }
        }
        for (int i = 0; i <= MONTHS_BACK; i++) {
            wanted.add("audit_event_p" + MONTH.format(today.withDayOfMonth(1).minusMonths(i)));
        }
        List<String> existing = jdbc.queryForList(
                "SELECT relname FROM pg_class WHERE relname = ANY(?) AND relnamespace = current_schema()::regnamespace",
                String.class,
                (Object) wanted.toArray(String[]::new));
        for (String name : wanted) {
            if (!existing.contains(name)) {
                create(jdbc, name);
            }
        }
    }

    /** The partition of {@code table} that holds {@code day}, daily or monthly as the table is. */
    public static void ensure(JdbcTemplate jdbc, String table, LocalDate day) {
        boolean monthly = table.equals("audit_event");
        LocalDate start = monthly ? day.withDayOfMonth(1) : day;
        create(jdbc, table + "_p" + (monthly ? MONTH : DAY).format(start));
    }

    private static void create(JdbcTemplate jdbc, String name) {
        String table = name.substring(0, name.lastIndexOf("_p"));
        String digits = name.substring(name.lastIndexOf("_p") + 2);
        boolean monthly = digits.length() == 6;
        LocalDate start = monthly ? LocalDate.parse(digits + "01", DAY) : LocalDate.parse(digits, DAY);
        LocalDate next = monthly ? start.plusMonths(1) : start.plusDays(1);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + name + " (LIKE " + table
                + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
        Boolean attached = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid = to_regclass(?))", Boolean.class, name);
        if (!Boolean.TRUE.equals(attached)) {
            jdbc.execute("ALTER TABLE " + table + " ATTACH PARTITION " + name + " FOR VALUES FROM ('"
                    + BOUND.format(start) + "') TO ('" + BOUND.format(next) + "')");
        }
    }
}
