/**
 * The approval gate's contract (ADR-0179): what a gated operation declares, what an approval provider
 * answers, and what a held operation looks like. Types, and the registries that find the provider and the operations of the
 * plugins attached to this replica; the engine lives elsewhere. Depends on the plugin contract for
 * {@code @PluginApi} and the plugin bridge.
 */
@ApplicationModule(
        displayName = "Approval gate contract",
        allowedDependencies = {"kernel.core", "kernel.plugin", "kernel.plugin :: descriptor"})
package io.github.sudoitir.artemisstudio.kernel.gate;

import org.springframework.modulith.ApplicationModule;
