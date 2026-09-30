package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The audit trail as a store. Kept forever unless an administrator sets a retention of at least 30
 * days. It lives here rather than in {@code kernel.audit}, which the lifecycle depends on.
 *
 * <p>Rows are deleted by {@code ts} alone. Nothing references {@code audit_event} with a foreign
 * key (audit outlives what it names, ADR-0072), and {@code parent_id} has none either, so a parent
 * may go before its children without a constraint failing. The {@code PURGE_STORE} event of the
 * running purge is newer than any cutoff, so the purge never removes its own record.
 */
@Component
class AuditStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "audit_event";
    private static final String PREDICATE = "ts < ?";

    private final JdbcTemplate jdbc;

    AuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return new StoreDef(
                "audit", "Audit events", List.of(TABLE), StoreDef.QuotaUnit.ROWS, null, Duration.ofDays(30), null);
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, List.of(TABLE));
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, TABLE, PREDICATE, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, PREDICATE, cutoff, limit);
    }
}
