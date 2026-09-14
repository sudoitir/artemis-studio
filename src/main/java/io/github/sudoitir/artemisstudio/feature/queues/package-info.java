/**
 * Queue, address and divert lifecycle across a cluster.
 */
@ApplicationModule(
        displayName = "Queues",
        allowedDependencies = {
            "kernel.core",
            "kernel.plugin",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.queues;

import org.springframework.modulith.ApplicationModule;
