/**
 * Alert rules, evaluation, firing and notification delivery.
 */
@ApplicationModule(
        displayName = "Alerting",
        allowedDependencies = {
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
package io.github.sudoitir.artemisstudio.feature.alerting;

import org.springframework.modulith.ApplicationModule;
