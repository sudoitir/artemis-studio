package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the audit trail in commit order, for whatever carries it elsewhere. {@link #after} never
 * skips a record: one that commits late is returned once it has, and a record that changed since
 * it was last returned comes again at its later position.
 */
@PluginApi
@Service
@RequiredArgsConstructor
public class AuditFeed {

    /** The most records one read returns. */
    public static final int MAX_LIMIT = 1000;

    private static final String SELECT = """
            SELECT id, ts, action, target_type, target_name, cluster_id, cluster_name, user_id, username,
                   outcome, error, affected_count, request_id, params::text, host(source_ip), node_id, dry_run,
                   outcome_detail::text, parent_id, pos_txid::text, pos_seq
            FROM audit_event""";

    private final JdbcTemplate jdbc;

    /** The records after {@code after} in commit order, at most {@code limit} (capped at {@link #MAX_LIMIT}). */
    @Transactional(readOnly = true)
    public List<AuditRecord> after(AuditPosition after, int limit) {
        return jdbc.query(SELECT + """

                        WHERE (pos_txid, pos_seq) > (?::text::xid8, ?)
                          AND pos_txid < pg_snapshot_xmin(pg_current_snapshot())
                        ORDER BY pos_txid, pos_seq
                        LIMIT ?""", AuditFeed::record, Long.toString(after.txid()), after.seq(), capped(limit));
    }

    /** The records with an id above {@code afterId}, in id order: the order they were begun in. */
    @Transactional(readOnly = true)
    public List<AuditRecord> byId(long afterId, int limit) {
        return jdbc.query(SELECT + " WHERE id > ? ORDER BY id LIMIT ?", AuditFeed::record, afterId, capped(limit));
    }

    /** The time of the oldest record still held, empty when there is none. */
    @Transactional(readOnly = true)
    public Optional<Instant> retainedFrom() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT min(ts) FROM audit_event", Timestamp.class))
                .map(Timestamp::toInstant);
    }

    private static int capped(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    private static AuditRecord record(ResultSet rs, int row) throws SQLException {
        return new AuditRecord(
                rs.getLong(1),
                rs.getTimestamp(2).toInstant(),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getObject(6, UUID.class),
                rs.getString(7),
                rs.getObject(8, UUID.class),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11),
                rs.getObject(12, Long.class),
                rs.getString(13),
                rs.getString(14),
                rs.getString(15),
                rs.getObject(16, UUID.class),
                rs.getBoolean(17),
                rs.getString(18),
                rs.getObject(19, Long.class),
                new AuditPosition(Long.parseLong(rs.getString(20)), rs.getLong(21)));
    }
}
