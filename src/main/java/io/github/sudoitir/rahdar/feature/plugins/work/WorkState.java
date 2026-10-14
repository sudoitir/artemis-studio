package io.github.sudoitir.rahdar.feature.plugins.work;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** Whether Rahdar lets a unit of work run. */
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
