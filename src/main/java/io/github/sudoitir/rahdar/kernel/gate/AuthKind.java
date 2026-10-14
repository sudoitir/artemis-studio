package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** How the requester authenticated. */
@PluginApi
public enum AuthKind {
    /** An interactive browser session. */
    SESSION,
    /** A personal API token, including the CLI. */
    TOKEN,
    /** An assistant calling an MCP tool. */
    AGENT
}
