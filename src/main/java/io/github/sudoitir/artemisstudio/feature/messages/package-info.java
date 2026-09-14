/**
 * Message browse, send and the destructive message operations, with dead letters.
 */
@ApplicationModule(
        displayName = "Messages",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.governance",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.messages;

import org.springframework.modulith.ApplicationModule;
