/**
 * Runtime settings assembled from module contributions, and deploy-time properties.
 */
@ApplicationModule(
        displayName = "Settings",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security"
        })
package io.github.sudoitir.artemisstudio.kernel.settings;

import org.springframework.modulith.ApplicationModule;
