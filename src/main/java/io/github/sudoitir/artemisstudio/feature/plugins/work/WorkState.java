package io.github.sudoitir.artemisstudio.feature.plugins.work;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** Whether Studio lets a unit of work run. */
@PluginApi
public enum WorkState {
    /** Its owner held every need at the last check. */
    ACTIVE,
    /**
     * Its owner lost a need. It does not run, and stays suspended until the owner holds the needs
     * again and {@link OwnerWork#enable} is called.
     */
    SUSPENDED
}
