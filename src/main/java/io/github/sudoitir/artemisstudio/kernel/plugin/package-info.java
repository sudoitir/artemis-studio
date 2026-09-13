/**
 * The extension contract (ADR-0070): what a module declares, which modules an
 * installation contains, which are enabled, and the manifest that publishes it.
 * Depends on nothing else in Studio.
 */
@ApplicationModule(
        displayName = "Plugin contract",
        allowedDependencies = {"kernel.core"})
package io.github.sudoitir.artemisstudio.kernel.plugin;

import org.springframework.modulith.ApplicationModule;
