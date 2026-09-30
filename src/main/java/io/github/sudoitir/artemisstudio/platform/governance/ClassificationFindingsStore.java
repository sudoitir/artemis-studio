package io.github.sudoitir.artemisstudio.platform.governance;

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
 * The classification inbox under the data lifecycle (ADR-0134). A finding is one row per
 * (address, location, field path, class), upserted whenever the pattern is seen again, so the
 * table is bounded by distinct patterns, not by traffic. An OPEN finding not seen for the whole
 * retention is stale and goes; a confirmed or dismissed one is an operator's decision and stays.
 */
@Component
@RequiredArgsConstructor
class ClassificationFindingsStore implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "classification_finding";
    private static final String STALE = "last_seen_at < ? AND status = 'OPEN'";

    private static final StoreDef DEF = new StoreDef(
            "classification-findings",
            "Classification findings",
            List.of(TABLE),
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
        return LifecycleSql.estimate(jdbc, TABLE, STALE, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, STALE, cutoff, limit);
    }
}
