/**
 * Declared broker configuration, drift, apply and config diff.
 */
@ApplicationModule(
        displayName = "Broker configuration",
        allowedDependencies = {
            "feature.alerting",
            "feature.queues",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import org.springframework.modulith.ApplicationModule;
