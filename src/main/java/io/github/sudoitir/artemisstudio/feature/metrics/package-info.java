/**
 * Queue metric series.
 */
@ApplicationModule(
        displayName = "Metrics",
        allowedDependencies = {
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.plugin :: descriptor",
            "kernel.security",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.metrics;

import org.springframework.modulith.ApplicationModule;
