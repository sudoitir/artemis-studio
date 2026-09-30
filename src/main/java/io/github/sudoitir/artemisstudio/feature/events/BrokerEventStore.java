package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
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
import org.springframework.stereotype.Component;

/**
 * The {@code broker_event} history under the data lifecycle (ADR-0028, ADR-0134). Volume follows
 * broker chatter Studio does not control, so retention is what bounds it next to the writer's buffer.
 */
@Component
class BrokerEventStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "broker_event";
    private static final String OLD = "received_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "broker-events",
            "Broker events",
            List.of(TABLE),
            QuotaUnit.ROWS,
            Duration.ofHours(72),
            Duration.ofHours(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;

    BrokerEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
        return LifecycleSql.estimate(jdbc, TABLE, OLD, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, OLD, cutoff, limit);
    }
}
