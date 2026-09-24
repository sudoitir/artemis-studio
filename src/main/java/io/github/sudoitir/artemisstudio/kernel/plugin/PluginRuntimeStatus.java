package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.time.Duration;
import java.util.Optional;

/**
 * Whether a plugin id is currently in a Brief-maintenance window (design.md, task 6.5) — the same
 * fact {@link io.github.sudoitir.artemisstudio.kernel.plugin.web.PluginGateway} answers a
 * {@code 503 plugin-updating} from, exposed to other modules' bridges (MCP, in particular) so a
 * call reaching a plugin through a different channel gets the same answer. Implemented in
 * {@code kernel.plugin.internal.runtime}, which holds the actual per-plugin activation state.
 */
public interface PluginRuntimeStatus {

    /** Empty when the plugin is not mid-update; the retry hint otherwise. */
    Optional<Duration> updating(String pluginId);
}
