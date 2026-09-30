package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The captured request and reply bodies inside {@code rr_event.detail}, under the data lifecycle
 * (ADR-0134). Purging strips the payload keys from old events and keeps the events, so a flow's timeline
 * outlives its bodies; a timeline that lost a body simply shows the event without one.
 */
class CapturedPayloadStore implements ManagedStore {

    private static final StoreDef DEF = new StoreDef(
            "captured-payloads",
            "Captured payloads",
            List.of("rr_event"),
            QuotaUnit.BYTES,
            Duration.ofDays(3),
            Duration.ofHours(1),
            Duration.ofDays(90));

    /** {@code jsonb_exists}, not the {@code ?} operator, which JDBC would read as a placeholder. */
    private static final String HAS_PAYLOAD = "jsonb_exists(detail, '" + RrPayloads.BODY_PREVIEW + "')";

    private static final String KEYS =
            RrPayloads.KEYS.stream().map(k -> "'" + k + "'").collect(Collectors.joining(","));
    private static final String WITHOUT = "(detail - ARRAY[" + KEYS + "])";

    /** What stripping frees for one event: its detail's size minus the size left over. */
    private static final String FREED = "octet_length(detail::text) - octet_length(" + WITHOUT + "::text)";

    private static final String OLD = "ts < ? AND " + HAS_PAYLOAD;

    private final JdbcTemplate jdbc;

    CapturedPayloadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    /** A real count: only the events that hold a body, and the bytes that stripping would free. */
    @Override
    public StoreUsage usage() {
        return jdbc.queryForObject(
                "SELECT count(*), coalesce(sum(" + FREED + "), 0) FROM rr_event WHERE " + HAS_PAYLOAD,
                (rs, i) -> new StoreUsage(rs.getLong(1), rs.getLong(2)));
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return jdbc.queryForObject(
                "SELECT count(*), coalesce(sum(" + FREED + "), 0) FROM rr_event WHERE " + OLD,
                (rs, i) -> new PurgeEstimate(rs.getLong(1), rs.getLong(2)),
                Timestamp.from(cutoff));
    }

    /** By {@code ctid}, like {@code LifecycleSql.deleteBatch}; an emptied detail becomes null, as it is when recorded. */
    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return jdbc.update(
                "UPDATE rr_event SET detail = nullif(" + WITHOUT + ", '{}'::jsonb) WHERE ctid = ANY(ARRAY(SELECT ctid"
                        + " FROM rr_event WHERE " + OLD + " LIMIT ?))",
                Timestamp.from(cutoff),
                limit);
    }
}
