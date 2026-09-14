/**
 * Request-reply expectations, correlation, latency and timeouts.
 */
@ApplicationModule(
        displayName = "Request-reply tracing",
        allowedDependencies = {
            "feature.messages",
            "feature.sql",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
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
package io.github.sudoitir.artemisstudio.feature.rr;

import org.springframework.modulith.ApplicationModule;
