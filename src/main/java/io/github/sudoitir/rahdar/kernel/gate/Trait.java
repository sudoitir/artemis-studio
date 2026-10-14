package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** What kind of operation a request is, for a provider's policies to match on. */
@PluginApi
public enum Trait {
    /** Destroys or moves data: purge, delete, move. */
    DESTRUCTIVE,
    /** Acts on many resources at once. */
    BULK,
    /** Changes Rahdar's operational settings. */
    SETTINGS,
    /** Changes who may do what: users, roles, teams, tokens, plugins, clusters. */
    ACCESS_CONTROL,
    /** Could weaken or switch off the gate itself; a provider should always hold it. */
    GATE_INTEGRITY
}
