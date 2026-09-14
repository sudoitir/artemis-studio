/**
 * Broker notification history and its live topic.
 */
@ApplicationModule(
        displayName = "Broker events",
        allowedDependencies = {
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.governance",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.events;

import org.springframework.modulith.ApplicationModule;
