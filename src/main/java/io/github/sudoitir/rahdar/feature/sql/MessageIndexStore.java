package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.Grain;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionDrops;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code message-index} store (ADR-0134): indexed and captured message payload in {@code
 * message_index}, purged past the store's retention, which caps every subscription's own (ADR-0059).
 * A batch drops one daily partition whose whole day is older than the cutoff (ADR-0193). {@link
 * MessageIndexRetention} trims what outlives a shorter subscription retention.
 */
@Component
public class MessageIndexStore implements HousekeepingContributor, ManagedStore {

    public static final String ID = "message-index";

    private static final String TABLE = "message_index";

    /** One update per re-observation: leave room for HOT updates, and analyze often for the GIN indexes. */
    static final PartitionSpec PARTITIONS = new PartitionSpec(
            TABLE,
            "observed_at",
            Grain.DAY,
            7,
            "fillfactor=90, autovacuum_vacuum_scale_factor=0.05, autovacuum_analyze_scale_factor=0.02");
    /**
     * 90 days by default, the longest a subscription may keep: the store's retention caps every
     * subscription's, so a shorter default would silently cut short a subscription an operator set
     * to keep longer. Lowering it is the operator's call, with the preview showing what goes.
     */
    private static final StoreDef DEF = new StoreDef(
            ID,
            "Message index",
            List.of(TABLE),
            QuotaUnit.BYTES,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;
    private final PartitionDrops drops;

    public MessageIndexStore(JdbcTemplate jdbc, PartitionDrops drops) {
        this.jdbc = jdbc;
        this.drops = drops;
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
        return LifecycleSql.usage(jdbc, List.of(TABLE));
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return drops.preview(PARTITIONS, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return drops.dropOldest(PARTITIONS, cutoff);
    }
}
