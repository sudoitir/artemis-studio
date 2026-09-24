/**
 * The per-cluster server-sent event stream and its topic registry.
 */
@ApplicationModule(
        displayName = "Event stream",
        allowedDependencies = {
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.plugin :: descriptor",
            "kernel.security",
            "kernel.settings"
        })
package io.github.sudoitir.artemisstudio.kernel.stream;

import org.springframework.modulith.ApplicationModule;
