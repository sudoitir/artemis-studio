package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditHold;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditPosition;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard.Plan;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard.Purged;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail as a store. Kept forever unless an administrator sets a retention of at least 30
 * days. It lives here rather than in {@code kernel.audit}, which the lifecycle depends on.
 *
 * <p>Rows are deleted by {@code ts}, then spared what {@link AuditRetentionGuard} says to keep
 * (ADR-0190): a record a receiver has not acknowledged, until it is older than the longest
 * unacknowledged age, and every record a retention hold covers. Nothing references {@code audit_event} with a foreign
 * key (audit outlives what it names, ADR-0072), and {@code parent_id} has none either, so a parent
 * may go before its children without a constraint failing. The {@code PURGE_STORE} event of the
 * running purge is newer than any cutoff, so the purge never removes its own record.
 */
@Component
class AuditStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "audit_event";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditRetentionGuard guard;

    AuditStore(JdbcTemplate jdbc, TransactionTemplate tx, AuditRetentionGuard guard) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.guard = guard;
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
        List<Object> args = new ArrayList<>();
        String where = where(cutoff, guard.plan(), args);
        Long rows =
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE " + where, Long.class, args.toArray());
        StoreUsage usage = usage();
        long bytesPerRow = usage.rows() > 0 ? usage.bytes() / usage.rows() : 0;
        long n = rows == null ? 0 : rows;
        return new PurgeEstimate(n, n * bytesPerRow);
    }

    /**
     * Asks the guard before every batch, so a hold added meanwhile is respected, and refuses the
     * whole run ({@link io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditPurgeRefused}) when it cannot answer.
     */
    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(cutoff, guard.plan(), args);
        args.add(limit);
        List<Purged> purged = tx.execute(status -> {
            List<Purged> removed = jdbc.query(
                    "DELETE FROM " + TABLE + " WHERE id IN (SELECT id FROM " + TABLE + " WHERE " + where
                            + " ORDER BY id LIMIT ?) RETURNING pos_txid::text, pos_seq, ts",
                    (rs, i) -> new Purged(
                            new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                            rs.getTimestamp(3).toInstant()),
                    args.toArray());
            guard.recordPurged(removed);
            return removed;
        });
        return purged == null ? 0 : purged.size();
    }

    /**
     * Older than the cutoff, and either older than the longest a receiver is waited for or acknowledged by
     * every receiver, and covered by no hold. Values are bound, never written into the SQL.
     */
    private static String where(Instant cutoff, Plan plan, List<Object> args) {
        StringBuilder sql = new StringBuilder("ts < ? AND (ts < ?");
        args.add(Timestamp.from(cutoff));
        args.add(Timestamp.from(plan.maxAgeCutoff()));
        if (plan.floor() == null) {
            sql.append(" OR TRUE");
        } else {
            sql.append(" OR (pos_txid, pos_seq) <= (?::text::xid8, ?)");
            args.add(Long.toString(plan.floor().txid()));
            args.add(plan.floor().seq());
        }
        sql.append(")");
        for (AuditHold hold : plan.holds()) {
            sql.append(" AND NOT (");
            List<String> conditions = new ArrayList<>();
            condition(conditions, args, "ts >= ?", hold.from() == null ? null : Timestamp.from(hold.from()));
            condition(conditions, args, "ts <= ?", hold.to() == null ? null : Timestamp.from(hold.to()));
            condition(conditions, args, "cluster_id = ?", hold.clusterId());
            condition(conditions, args, "username = ?", hold.username());
            condition(conditions, args, "action = ?", hold.action());
            condition(conditions, args, "target_type = ?", hold.targetType());
            condition(conditions, args, "target_name = ?", hold.targetName());
            sql.append(conditions.isEmpty() ? "TRUE" : String.join(" AND ", conditions))
                    .append(")");
        }
        return sql.toString();
    }

    private static void condition(List<String> conditions, List<Object> args, String sql, Object value) {
        if (value != null) {
            conditions.add(sql);
            args.add(value);
        }
    }
}
