package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * Facts about the current request that the gate reads, bound by the entry point. Unbound, the origin
 * follows the principal (a session or an API token).
 */
@PluginApi
public final class GateContext {

    /** The response header that names a held operation's id. */
    public static final String HELD_HEADER = "X-Rahdar-Held-Operation";

    /** How the requester came in; MCP tools bind {@link AuthKind#AGENT}. */
    public static final ScopedValue<AuthKind> ORIGIN = ScopedValue.newInstance();

    private GateContext() {}
}
