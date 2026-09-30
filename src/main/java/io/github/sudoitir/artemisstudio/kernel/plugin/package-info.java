/**
 * The extension contract (ADR-0070): what a module declares, which modules an
 * installation contains, which are enabled, and the manifest that publishes it.
 * Depends only on the core and on the replicas, whose crash count guards its boot.
 */
@ApplicationModule(
        displayName = "Plugin contract",
        allowedDependencies = {"kernel.core", "kernel.replica"})
package io.github.sudoitir.artemisstudio.kernel.plugin;

import org.springframework.modulith.ApplicationModule;
