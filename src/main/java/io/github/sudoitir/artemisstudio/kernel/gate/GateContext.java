package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Facts about the current request that the gate reads, bound by the entry point. Unbound, the origin
 * follows the principal (a session or an API token) and there is no reason.
 */
@PluginApi
public final class GateContext {

    /** The request header that carries the requester's reason. */
    public static final String REASON_HEADER = "X-Studio-Approval-Reason";

    /** The MCP tool argument that carries the requester's reason. */
    public static final String REASON_ARGUMENT = "approvalReason";

    /** The response header that names a held operation's id. */
    public static final String HELD_HEADER = "X-Studio-Held-Operation";

    /** How the requester came in; MCP tools bind {@link AuthKind#AGENT}. */
    public static final ScopedValue<AuthKind> ORIGIN = ScopedValue.newInstance();

    /** Why the requester asks, from {@link #REASON_HEADER} or {@link #REASON_ARGUMENT}. */
    public static final ScopedValue<String> REASON = ScopedValue.newInstance();

    private GateContext() {}
}
