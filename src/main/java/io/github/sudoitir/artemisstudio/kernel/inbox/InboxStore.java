package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Inbox notices under the data lifecycle (ADR-0134): a notice goes once it is older than the store's
 * retention, 90 days by default; a read one goes sooner, after {@link InboxSettings#READ_RETENTION}
 * since it was read; and one with a lifetime of its own goes when that passes.
 */
@Component
class InboxStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "inbox_item";

    private static final StoreDef DEF = new StoreDef(
            "inbox",
            "Inbox notices",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    InboxStore(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
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
        return LifecycleSql.estimate(jdbc, TABLE, predicate(), cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, predicate(), cutoff, limit);
    }

    /** The one {@code ?} is the store's cutoff; the read retention is a number of seconds, never user text. */
    private String predicate() {
        long readSeconds = settings.duration(InboxSettings.READ_RETENTION).toSeconds();
        return "created_at < ? OR expires_at < now() OR read_at < now() - make_interval(secs => " + readSeconds + ")";
    }
}
