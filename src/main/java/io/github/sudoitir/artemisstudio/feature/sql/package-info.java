/**
 * SQL over queues, the message index and message capture.
 */
@ApplicationModule(
        displayName = "SQL console",
        allowedDependencies = {
            "feature.messages",
            "feature.queues",
            "feature.routing",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.sql;

import org.springframework.modulith.ApplicationModule;
