/**
 * The agent surface over MCP: server, tool catalogue, help, resources and prompts.
 */
@ApplicationModule(
        displayName = "MCP server",
        allowedDependencies = {
            "kernel.core",
            "kernel.plugin",
            "kernel.plugin :: descriptor",
            "kernel.security",
            "kernel.settings",
            "kernel.settings :: web",
            "platform.broker",
            "platform.clusters",
            "platform.clusters :: web"
        })
package io.github.sudoitir.artemisstudio.platform.mcp;

import org.springframework.modulith.ApplicationModule;
