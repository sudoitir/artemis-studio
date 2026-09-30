package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Request-reply flows under the data lifecycle (ADR-0134). {@code rr_event} rows go with their flow
 * through {@code ON DELETE CASCADE} (fk_rr_event_flow).
 */
class RrFlowStore implements ManagedStore {

    private static final String OLD = "requested_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "rr-flows",
            "Request-reply flows",
            List.of("rr_flow", "rr_event"),
            QuotaUnit.ROWS,
            Duration.ofDays(7),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;

    RrFlowStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, DEF.tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, "rr_flow", OLD, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, "rr_flow", OLD, cutoff, limit);
    }
}
