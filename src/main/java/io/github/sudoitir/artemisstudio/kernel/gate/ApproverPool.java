package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Who may decide what the armed approval provider holds: the enabled users who hold its approver permission for the
 * whole installation. An approval needs a person other than the requester, so with fewer than {@link #QUORUM} such
 * users a held request can never be decided by anyone but the one who made it, and the gate would lock the
 * installation. A provider asks {@link #quorate()} before it starts holding, and Studio refuses an access change that
 * would take the pool below it while the provider enforces.
 */
@PluginApi
public interface ApproverPool {

    /** The fewest approvers an enforcing gate may have: the requester's own approval never counts. */
    int QUORUM = 2;

    /** How many enabled users hold the approver permission for the whole installation now; 0 without a provider. */
    int size();

    /** Whether {@link #size()} reaches {@link #QUORUM}. */
    default boolean quorate() {
        return size() >= QUORUM;
    }
}
