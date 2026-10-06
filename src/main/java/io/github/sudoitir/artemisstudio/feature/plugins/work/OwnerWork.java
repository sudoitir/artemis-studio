package io.github.sudoitir.artemisstudio.feature.plugins.work;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A plugin's door for work that runs later as its owner, such as a schedule that moves messages or a
 * rule that sends them. Inject it into a plugin bean; Studio puts one bound to the plugin into its context.
 *
 * <p>The plugin declares what the work needs, a list of {@link WorkNeed}s, when it publishes the work. Studio
 * refuses the publish when the owner lacks any of them, naming each, and checks them again at
 * {@link #beforeRun}: a plugin starts a run only when that returns a {@linkplain WorkStatus#runnable runnable}
 * status. When the owner has lost a need the work is suspended with the reason, which survives restarts, and
 * it stays suspended until the owner holds the needs again and {@link #enable} turns it on. The checks use the
 * owner's account as it stands now, with the team roles and shares that give them access to a queue or address.
 */
@PluginApi
public final class OwnerWork {

    private final OwnerWorkService service;
    private final String pluginId;

    OwnerWork(OwnerWorkService service, String pluginId) {
        this.service = service;
        this.pluginId = pluginId;
    }

    /**
     * Store the work under its key, replacing any under that key, as active.
     *
     * @throws WorkRefusedException naming every need the owner lacks, or when the owner is unknown or disabled
     */
    public WorkStatus publish(String key, UUID ownerUserId, List<WorkNeed> needs) {
        return service.publish(pluginId, key, ownerUserId, needs);
    }

    /**
     * Check the work's needs now, before a run. Active work whose owner lost a need becomes suspended, and the
     * answer says why; suspended work stays suspended.
     *
     * @throws WorkRefusedException when no work is published under the key
     */
    public WorkStatus beforeRun(String key) {
        return service.beforeRun(pluginId, key);
    }

    /**
     * Turn suspended work back on, once its owner holds every need again.
     *
     * @throws WorkRefusedException naming each need still missing, or when no work is published under the key
     */
    public WorkStatus enable(String key) {
        return service.enable(pluginId, key);
    }

    public Optional<WorkStatus> status(String key) {
        return service.status(pluginId, key);
    }

    public List<WorkStatus> all() {
        return service.all(pluginId);
    }

    /** Remove the work. Removing an unknown key is a no-op. */
    public boolean withdraw(String key) {
        return service.withdraw(pluginId, key);
    }
}
