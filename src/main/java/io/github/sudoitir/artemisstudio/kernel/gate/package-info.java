/**
 * The approval gate's contract (ADR-0179): what a gated operation declares, what an approval provider
 * answers, and what a held operation looks like. Types only; the engine lives elsewhere. Depends on
 * the plugin contract only for {@code @PluginApi}.
 */
@ApplicationModule(
        displayName = "Approval gate contract",
        allowedDependencies = {"kernel.core", "kernel.plugin"})
package io.github.sudoitir.artemisstudio.kernel.gate;

import org.springframework.modulith.ApplicationModule;
