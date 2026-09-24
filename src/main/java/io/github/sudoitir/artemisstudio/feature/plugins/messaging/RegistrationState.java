package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** Where a registration stands, overall and on each node (ADR-0111). */
@PluginApi
public enum RegistrationState {
    /** Stored, and not yet made true on the broker; the next pass does that. */
    PENDING,
    /** Delivering. */
    ACTIVE,
    /** Its acting user is gone or lacks a permission it needs. It resumes when that changes. */
    SUSPENDED,
    /** The plugin is not running (disabled, uninstalled, or failed), so nothing is delivered. */
    INACTIVE,
    /** The broker refused it for a reason that will not change by itself; the detail says which. */
    FAILED,
    /** Another Studio instance sharing this database is delivering it on this node. */
    SERVED_ELSEWHERE
}
