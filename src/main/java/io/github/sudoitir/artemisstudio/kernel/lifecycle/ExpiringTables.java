package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The UNLOGGED counter and permit tables that count limits across replicas (ADR-0196), under the data
 * lifecycle (ADR-0134). Each row carries the time it lapses; a key that keeps being used rewrites its
 * own row, and this removes what a key that stopped left behind.
 */
@Component
@RequiredArgsConstructor
class ExpiringTables implements HousekeepingContributor {

    private static final String EXPIRES_AT = "expires_at";

    private final JdbcTemplate jdbc;

    @Override
    public List<ManagedStore> stores() {
        return List.of(
                new Expiring("heavy-permits", "Heavy request permits", "heavy_permit", EXPIRES_AT),
                new Expiring("login-attempts", "Failed sign-in counters", "login_attempt", EXPIRES_AT),
                new Expiring("upload-windows", "Plugin upload counters", "upload_window", "window_ends_at"),
                new Expiring("request-in-flight", "In-flight request counters", "request_in_flight", EXPIRES_AT));
    }

    /** One table whose rows are stale once {@code column} is past. */
    private final class Expiring implements ManagedStore {

        private final StoreDef def;
        private final String table;
        private final String stale;

        Expiring(String id, String label, String table, String column) {
            this.def = new StoreDef(
                    id,
                    label,
                    List.of(table),
                    StoreDef.QuotaUnit.ROWS,
                    Duration.ofHours(1),
                    Duration.ofMinutes(1),
                    Duration.ofDays(1));
            this.table = table;
            this.stale = column + " < ?";
        }

        @Override
        public StoreDef def() {
            return def;
        }

        @Override
        public StoreUsage usage() {
            return LifecycleSql.usage(jdbc, def.tables());
        }

        @Override
        public PurgeEstimate preview(Instant cutoff) {
            return LifecycleSql.estimate(jdbc, table, stale, cutoff);
        }

        @Override
        public long purgeBatch(Instant cutoff, int limit) {
            return LifecycleSql.deleteBatch(jdbc, table, stale, cutoff, limit);
        }
    }
}
