package io.github.sudoitir.artemisstudio.feature.plugins.work;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A unit of work as Studio holds it.
 *
 * @param key the plugin's own name for the work
 * @param ownerUserId the user it runs as
 * @param needs what the owner must hold for it to run
 * @param reason why it is suspended, in words, or {@code null} while it is active
 * @param updatedAt when it was published, checked into a different state, or enabled
 */
@PluginApi
public record WorkStatus(
        String key, UUID ownerUserId, List<WorkNeed> needs, WorkState state, String reason, Instant updatedAt) {

    /** Whether the work may run now. */
    public boolean runnable() {
        return state == WorkState.ACTIVE;
    }
}
