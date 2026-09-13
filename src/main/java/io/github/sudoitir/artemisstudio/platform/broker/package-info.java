/**
 * Jolokia and Core transport, rate limiting, capability probing and clock readings.
 */
@ApplicationModule(
        displayName = "Broker connectivity",
        allowedDependencies = {"kernel.core", "kernel.jobs", "kernel.plugin", "kernel.settings"})
package io.github.sudoitir.artemisstudio.platform.broker;

import org.springframework.modulith.ApplicationModule;
