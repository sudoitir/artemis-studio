package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditHold;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditPosition;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard.Plan;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditRetentionGuard.Purged;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.Grain;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionDrops;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionSpec;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
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
 *
 * <p>The table is partitioned by month (ADR-0193). A month that ended before the cutoff is dropped whole
 * when every row in it is eligible, which is far cheaper than deleting it; a month with one row that is
 * not (unacknowledged, or held) is purged row by row, so the gap records are written as they always were.
 */
@Component
class AuditStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "audit_event";

    static final PartitionSpec PARTITIONS = new PartitionSpec(
            TABLE,
            "ts",
            Grain.MONTH,
            3,
            "fillfactor=100, autovacuum_vacuum_insert_scale_factor=0, autovacuum_vacuum_insert_threshold=5000,"
                    + " autovacuum_analyze_scale_factor=0.02");

    /** The most unacknowledged rows a dropped month may hold; more are purged row by row, a batch at a time. */
    private static final int MAX_GAP_ROWS = 10_000;

    /**
     * Older than the cutoff, and either older than the longest a receiver is waited for, or acknowledged by every
     * receiver (no floor, or at or below it), and covered by no hold. A hold is one row of the unnest: a null field
     * matches anything, so a hold with no fields keeps everything and no holds keep nothing back.
     */
    private static final String ELIGIBLE = """
            e.ts < ?
              AND (e.ts < ? OR ? OR (e.pos_txid, e.pos_seq) <= (?::text::xid8, ?::bigint))
              AND NOT EXISTS (
                SELECT 1 FROM unnest(?::timestamptz[], ?::timestamptz[], ?::uuid[], ?::text[], ?::text[], ?::text[], ?::text[])
                    AS h(f, t, c, u, a, tt, tn)
                WHERE (h.f IS NULL OR e.ts >= h.f)
                  AND (h.t IS NULL OR e.ts <= h.t)
                  AND (h.c IS NULL OR e.cluster_id = h.c)
                  AND (h.u IS NULL OR e.username = h.u)
                  AND (h.a IS NULL OR e.action = h.a)
                  AND (h.tt IS NULL OR e.target_type = h.tt)
                  AND (h.tn IS NULL OR e.target_name = h.tn))""";

    private static final String COUNT = "SELECT count(*) FROM audit_event e WHERE " + ELIGIBLE;

    private static final String DELETE = "DELETE FROM audit_event WHERE id IN (SELECT e.id FROM audit_event e WHERE "
            + ELIGIBLE + " ORDER BY e.id LIMIT ?) RETURNING pos_txid::text, pos_seq, ts";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditRetentionGuard guard;
    private final PartitionDrops drops;

    AuditStore(JdbcTemplate jdbc, TransactionTemplate tx, AuditRetentionGuard guard, PartitionDrops drops) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.guard = guard;
        this.drops = drops;
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
        Long rows = jdbc.query(COUNT, binder(cutoff, guard.plan(), null), rs -> rs.next() ? rs.getLong(1) : 0L);
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
        Plan plan = guard.plan();
        for (String partition : drops.expired(PARTITIONS, cutoff)) {
            long dropped = dropIfWhollyEligible(partition, cutoff, plan);
            if (dropped > 0) {
                return dropped;
            }
        }
        PreparedStatementSetter binder = binder(cutoff, plan, limit);
        List<Purged> purged = tx.execute(status -> {
            List<Purged> removed = jdbc.query(
                    DELETE,
                    binder,
                    (rs, i) -> new Purged(
                            new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                            rs.getTimestamp(3).toInstant()));
            guard.recordPurged(removed);
            return removed;
        });
        return purged == null ? 0 : purged.size();
    }

    /**
     * Drops {@code partition} when every row in it is {@link #ELIGIBLE}, after recording the gaps for the
     * rows a receiver had not acknowledged, exactly as the row purge would have. The gaps are committed
     * before the drop, so a record is never gone without its gap.
     *
     * @return the rows dropped, counted: the purge is audited with this number. 0 when the partition was kept
     */
    private long dropIfWhollyEligible(String partition, Instant cutoff, Plan plan) {
        // Names come from the catalog, not from a caller: see PartitionDrops.expired.
        Boolean keep = jdbc.query(
                "SELECT EXISTS (SELECT 1 FROM " + partition + " e WHERE NOT (" + ELIGIBLE + "))",
                binder(cutoff, plan, null),
                rs -> rs.next() && rs.getBoolean(1));
        if (Boolean.TRUE.equals(keep)) {
            return 0;
        }
        List<Purged> unacknowledged = plan.floor() == null ? List.of() : unacknowledged(partition, plan.floor());
        if (unacknowledged.size() > MAX_GAP_ROWS) {
            return 0;
        }
        Timestamp newestTs = jdbc.queryForObject("SELECT max(ts) FROM " + partition, Timestamp.class);
        Instant newest = newestTs == null ? null : newestTs.toInstant();
        List<Purged> purged = new ArrayList<>(unacknowledged);
        if (newest != null) {
            // Stands for the acknowledged rows: it moves "complete from" and is behind every cursor.
            purged.add(new Purged(plan.floor() == null ? AuditPosition.START : plan.floor(), newest));
        }
        tx.executeWithoutResult(status -> guard.recordPurged(purged));
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + partition, Long.class);
        drops.drop(PARTITIONS, partition);
        return rows == null ? 0 : rows;
    }

    private List<Purged> unacknowledged(String partition, AuditPosition floor) {
        return jdbc.query(
                "SELECT pos_txid::text, pos_seq, ts FROM " + partition
                        + " WHERE (pos_txid, pos_seq) > (?::text::xid8, ?::bigint) LIMIT " + (MAX_GAP_ROWS + 1),
                (rs, i) -> new Purged(
                        new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                        rs.getTimestamp(3).toInstant()),
                Long.toString(floor.txid()),
                floor.seq());
    }

    /**
     * Binds {@link #ELIGIBLE}: the cutoff, the longest an unacknowledged record is kept, the floor, and the holds as
     * parallel arrays. Values are only ever bound, never written into the SQL.
     */
    private static PreparedStatementSetter binder(Instant cutoff, Plan plan, Integer limit) {
        return ps -> {
            var connection = ps.getConnection();
            List<AuditHold> holds = plan.holds();
            int i = 1;
            ps.setTimestamp(i++, Timestamp.from(cutoff));
            ps.setTimestamp(i++, Timestamp.from(plan.maxAgeCutoff()));
            ps.setBoolean(i++, plan.floor() == null);
            if (plan.floor() == null) {
                ps.setNull(i++, Types.VARCHAR);
                ps.setNull(i++, Types.BIGINT);
            } else {
                ps.setString(i++, Long.toString(plan.floor().txid()));
                ps.setLong(i++, plan.floor().seq());
            }
            ps.setArray(
                    i++,
                    array(connection, "timestamptz", holds, h -> h.from() == null ? null : Timestamp.from(h.from())));
            ps.setArray(
                    i++, array(connection, "timestamptz", holds, h -> h.to() == null ? null : Timestamp.from(h.to())));
            ps.setArray(i++, array(connection, "uuid", holds, AuditHold::clusterId));
            ps.setArray(i++, array(connection, "text", holds, AuditHold::username));
            ps.setArray(i++, array(connection, "text", holds, AuditHold::action));
            ps.setArray(i++, array(connection, "text", holds, AuditHold::targetType));
            ps.setArray(i++, array(connection, "text", holds, AuditHold::targetName));
            if (limit != null) {
                ps.setInt(i, limit);
            }
        };
    }

    private static Array array(
            Connection connection, String type, List<AuditHold> holds, Function<AuditHold, Object> field)
            throws SQLException {
        return connection.createArrayOf(type, holds.stream().map(field).toArray());
    }
}
