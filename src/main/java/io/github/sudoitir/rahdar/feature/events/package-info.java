/**
 * Broker notification history and its live topic.
 */
@ApplicationModule(
        displayName = "Broker events",
        allowedDependencies = {
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.lifecycle::partition",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.governance",
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.feature.events;

import org.springframework.modulith.ApplicationModule;
