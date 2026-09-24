/**
 * Plugin administration (ADR-0099, ADR-0103): upload, review, activate, update, roll back,
 * disable, uninstall and purge, the installer tier and step-up re-authentication that gate them,
 * and the audit trail of every step.
 */
@ApplicationModule(
        displayName = "Plugins",
        allowedDependencies = {
            "kernel.audit",
            "kernel.audit::web",
            "kernel.core",
            "kernel.plugin",
            "kernel.plugin::descriptor",
            "kernel.plugin::host",
            "kernel.plugin::validation",
            "kernel.security"
        })
package io.github.sudoitir.artemisstudio.feature.plugins;

import org.springframework.modulith.ApplicationModule;
