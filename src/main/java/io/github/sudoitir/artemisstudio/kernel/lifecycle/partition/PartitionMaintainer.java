package io.github.sudoitir.artemisstudio.kernel.lifecycle.partition;

import io.github.sudoitir.artemisstudio.kernel.core.PartitionProvisioner;
import io.github.sudoitir.artemisstudio.kernel.core.SqlIdentifier;
import io.github.sudoitir.artemisstudio.kernel.core.StatementTimeout;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionCatalog.Child;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the partitions of every {@link PartitionSpec} in place (ADR-0193): created ahead of the writes,
 * frozen once their period has closed, and the parents analyzed daily, because autovacuum never
 * analyzes a partitioned parent.
 *
 * <p>It runs at start-up, in a lifecycle phase before scraping, capture and jobs, and every hour from
 * the {@code partitions} job. A partition is created as a plain table and then attached, which takes
 * {@code SHARE UPDATE EXCLUSIVE} on the parent where {@code PARTITION OF} would take {@code ACCESS EXCLUSIVE}.
 * A transaction-scoped advisory lock makes replicas that start together take turns.
 */
@Component
@Slf4j
public class PartitionMaintainer implements PartitionProvisioner, SmartLifecycle {

    /** Before everything else that starts: the first scrape, capture tail or job must find its partition. */
    private static final int PHASE = Integer.MIN_VALUE + 1000;

    private static final String LOCK = "partitions.maintain";
    private static final Duration ANALYZE_EVERY = Duration.ofDays(1);

    /** A closed partition can still receive a write from a clock that runs a little late. */
    private static final Duration FREEZE_AFTER = Duration.ofHours(1);

    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd '00:00:00+00'");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectProvider<PartitionSpec> specs;
    private final Clock clock;
    private volatile boolean running;

    PartitionMaintainer(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectProvider<PartitionSpec> specs, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.specs = specs;
        this.clock = clock;
    }

    /** The hourly run: partitions ahead, closed ones frozen, parents analyzed. */
    public void maintain() {
        ensureAhead();
        for (PartitionSpec spec : specs.orderedStream().toList()) {
            freezeClosed(spec);
            analyzeParent(spec);
        }
    }

    @Override
    public void ensureAhead() {
        RuntimeException failure = null;
        for (PartitionSpec spec : specs.orderedStream().toList()) {
            try {
                transactions.executeWithoutResult(status -> createAhead(spec));
            } catch (RuntimeException e) {
                log.error("Could not create the {} partitions", spec.table(), e);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The tables whose current period has no partition: what the {@code partitions} health check reports. */
    public List<String> missingCurrent() {
        List<String> missing = new ArrayList<>();
        for (PartitionSpec spec : specs.orderedStream().toList()) {
            LocalDate start = spec.grain().startOf(today());
            if (!PartitionCatalog.attached(jdbc, spec, spec.grain().name(spec.table(), start))) {
                missing.add(spec.table());
            }
        }
        return missing;
    }

    private void createAhead(PartitionSpec spec) {
        jdbc.execute(StatementTimeout.setLocal(StatementTimeout.LONG));
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, LOCK);
        LocalDate start = spec.grain().startOf(today());
        for (int i = 0; i <= spec.ahead(); i++) {
            LocalDate next = spec.grain().next(start);
            String name = spec.grain().name(spec.table(), start);
            // Identifiers cannot be bound: the name is the spec's table plus a formatted date, and the
            // bounds are formatted dates, so nothing here carries caller-supplied text.
            String partition = SqlIdentifier.quote(name);
            String parent = SqlIdentifier.quote(spec.table());
            jdbc.execute("CREATE TABLE IF NOT EXISTS %s (LIKE %s INCLUDING DEFAULTS INCLUDING CONSTRAINTS)%s"
                    .formatted(partition, parent, with(spec)));
            if (!PartitionCatalog.attached(jdbc, spec, name)) {
                jdbc.execute("ALTER TABLE %s ATTACH PARTITION %s FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(parent, partition, BOUND.format(start), BOUND.format(next)));
                log.info("Created {} partition {}", spec.table(), name);
            }
            start = next;
        }
    }

    private static String with(PartitionSpec spec) {
        return spec.storageParams().isEmpty() ? "" : " WITH (" + spec.storageParams() + ")";
    }

    private static boolean dueToFreeze(PartitionSpec spec, Child child, Instant now) {
        if (child.start() == null || child.detachPending() || child.frozen()) {
            return false;
        }
        Instant closed = spec.grain().next(child.start()).atStartOfDay().toInstant(ZoneOffset.UTC);
        return !closed.plus(FREEZE_AFTER).isAfter(now);
    }

    /** Freezes and analyzes a partition once its period has closed, and marks it so it is not done again. */
    private void freezeClosed(PartitionSpec spec) {
        Instant now = clock.instant();
        for (Child child : PartitionCatalog.children(jdbc, spec)) {
            if (!dueToFreeze(spec, child, now)) {
                continue;
            }
            try {
                PartitionCatalog.outsideTransaction(
                        jdbc,
                        "VACUUM (FREEZE, ANALYZE) " + SqlIdentifier.quote(child.name()),
                        "COMMENT ON TABLE " + SqlIdentifier.quote(child.name()) + " IS '" + PartitionCatalog.FROZEN
                                + "'");
            } catch (RuntimeException e) {
                log.warn("Could not freeze {}; the next run tries again", child.name(), e);
            }
        }
    }

    private void analyzeParent(PartitionSpec spec) {
        Timestamp last = jdbc.query(
                "SELECT last_analyze FROM pg_stat_all_tables WHERE relid = to_regclass(?)",
                rs -> rs.next() ? rs.getTimestamp(1) : null,
                spec.table());
        if (last != null && last.toInstant().plus(ANALYZE_EVERY).isAfter(clock.instant())) {
            return;
        }
        try {
            PartitionCatalog.outsideTransaction(jdbc, "ANALYZE " + spec.table());
        } catch (RuntimeException e) {
            log.warn("Could not analyze {}; the next run tries again", spec.table(), e);
        }
    }

    private LocalDate today() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    @Override
    public void start() {
        try {
            ensureAhead();
        } catch (RuntimeException e) {
            // Studio still starts: the health check reports the missing partition, and the hourly run retries.
            log.error("Start-up could not create every partition", e);
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
