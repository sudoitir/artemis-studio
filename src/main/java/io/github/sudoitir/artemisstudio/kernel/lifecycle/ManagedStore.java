package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;

/**
 * A store the data lifecycle purges, previews and reports (ADR-0134). The engine owns the policy,
 * the schedule, the once-per-installation lock and the audit; the store only knows its own tables.
 */
@PluginApi
public interface ManagedStore {

    StoreDef def();

    StoreUsage usage();

    /** What {@link #purgeBatch} would remove, in total, for {@code cutoff}. Deletes nothing. */
    PurgeEstimate preview(Instant cutoff);

    /**
     * Removes at most one bounded batch of what is older than {@code cutoff}, in its own short
     * transaction, so a concurrent writer waits for one batch at most. Called until it returns 0.
     *
     * @param limit the most rows one batch should touch; a partition store may drop one whole
     *     expired partition instead
     * @return how many rows it removed
     */
    long purgeBatch(Instant cutoff, int limit);
}
