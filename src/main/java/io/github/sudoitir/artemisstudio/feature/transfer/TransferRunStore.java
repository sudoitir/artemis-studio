package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transfer runs under the data lifecycle (ADR-0132): a run that is over for good goes once it is
 * older than the retention, and a preview nobody executed goes when it expires, whatever the
 * retention. The copy ledger goes with its run through {@code ON DELETE CASCADE}.
 *
 * <p>Only {@code SUCCEEDED}, {@code PARTIAL} and {@code RETURNED} runs are purged. An active run is
 * never touched, and neither is a resumable one ({@code STOPPED}, {@code INTERRUPTED},
 * {@code FAILED}): a move's held messages sit in staging under that record, and deleting it would
 * orphan them.
 */
@Component
class TransferRunStore implements ManagedStore, HousekeepingContributor {

    private static final String RUNS = "transfer_run";
    private static final String PREDICATE = "(state IN ('SUCCEEDED', 'PARTIAL', 'RETURNED')"
            + " AND coalesce(finished_at, created_at) < ?) OR (state = 'PREVIEWED' AND expires_at < now())";

    private final JdbcTemplate jdbc;

    TransferRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return new StoreDef(
                "transfer-runs",
                "Transfer runs",
                List.of(RUNS, "transfer_copied"),
                StoreDef.QuotaUnit.ROWS,
                Duration.ofDays(90),
                Duration.ofDays(1),
                Duration.ofDays(3650));
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, def().tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, RUNS, PREDICATE, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, RUNS, PREDICATE, cutoff, limit);
    }
}
