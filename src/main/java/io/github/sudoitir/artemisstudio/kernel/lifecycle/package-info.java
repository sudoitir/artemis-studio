/**
 * The data lifecycle (ADR-0134): one retention policy, quota and batched purge for every store
 * that grows with use, core or plugin, and the storage health of the tables behind them.
 */
@ApplicationModule(
        displayName = "Data lifecycle",
        allowedDependencies = {
            "kernel.audit",
            "kernel.gate",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings"
        })
package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import org.springframework.modulith.ApplicationModule;
