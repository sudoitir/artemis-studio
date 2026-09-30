package io.github.sudoitir.artemisstudio.feature.apitokens;

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
 * The request windows of tokens and owners that stopped calling, under the data lifecycle
 * (ADR-0134, ADR-0148). A key that keeps calling trims its own older minutes; this removes what a
 * key that stopped left behind.
 */
@Component
@RequiredArgsConstructor
class ApiRequestWindowStore implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "api_request_window";
    private static final String OLDER = "to_timestamp(minute * 60) < ?";

    private static final StoreDef DEF = new StoreDef(
            "api-request-windows",
            "API request rate windows",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofHours(1),
            Duration.ofMinutes(5),
            Duration.ofDays(1));

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
        return LifecycleSql.estimate(jdbc, TABLE, OLDER, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, OLDER, cutoff, limit);
    }
}
