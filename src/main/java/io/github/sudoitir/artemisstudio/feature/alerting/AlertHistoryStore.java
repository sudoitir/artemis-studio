package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The alert history under the data lifecycle (ADR-0132): resolved firings and finished deliveries
 * older than the retention. A firing that is still open is what {@code alert_state} and the
 * evaluator resolve later, and a pending delivery is still owed to a channel, so neither is ever
 * purged, however old.
 */
@Component
@RequiredArgsConstructor
class AlertHistoryStore implements HousekeepingContributor, ManagedStore {

    private static final String FIRINGS = "resolved_at < ?";
    private static final String DELIVERIES = "state <> 'PENDING' AND created_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "alert-history",
            "Alert history",
            List.of("alert_firing", "alert_delivery"),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
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
        PurgeEstimate firings = LifecycleSql.estimate(jdbc, "alert_firing", FIRINGS, cutoff);
        PurgeEstimate deliveries = LifecycleSql.estimate(jdbc, "alert_delivery", DELIVERIES, cutoff);
        return new PurgeEstimate(firings.rows() + deliveries.rows(), firings.bytes() + deliveries.bytes());
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        long firings = LifecycleSql.deleteBatch(jdbc, "alert_firing", FIRINGS, cutoff, limit);
        return firings > 0 ? firings : LifecycleSql.deleteBatch(jdbc, "alert_delivery", DELIVERIES, cutoff, limit);
    }
}
