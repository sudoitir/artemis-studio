package io.github.sudoitir.artemisstudio.feature.sql.web;

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

/** Query references that expired unredeemed, under the data lifecycle (ADR-0134). */
@Component
@RequiredArgsConstructor
class SqlQueryTicketStore implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "sql_query_ticket";
    private static final String EXPIRED = "expires_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "sql-query-tickets",
            "SQL query references",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofHours(1),
            Duration.ofMinutes(1),
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
        return LifecycleSql.estimate(jdbc, TABLE, EXPIRED, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, EXPIRED, cutoff, limit);
    }
}
