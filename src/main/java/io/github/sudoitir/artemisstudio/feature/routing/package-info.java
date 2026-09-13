/**
 * Divert and bridge views.
 */
@ApplicationModule(
        displayName = "Routing",
        allowedDependencies = {
            "feature.queues",
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "platform.broker",
            "platform.clusters"
        })
package io.github.sudoitir.artemisstudio.feature.routing;

import org.springframework.modulith.ApplicationModule;
