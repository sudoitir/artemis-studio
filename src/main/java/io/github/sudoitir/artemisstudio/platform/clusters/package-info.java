/**
 * Cluster registration, topology, HA state and the audited broker command executor.
 */
@ApplicationModule(
        displayName = "Clusters",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "platform.broker"
        })
package io.github.sudoitir.artemisstudio.platform.clusters;

import org.springframework.modulith.ApplicationModule;
