/**
 * Diagnostics: a redacted support bundle an administrator previews, trims and downloads, and the
 * environment summary behind "Report a bug" (ADR-0146). Everything is built in-process; nothing
 * leaves Studio.
 */
@ApplicationModule(
        displayName = "Diagnostics",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.plugin::host",
            "kernel.security",
            "kernel.settings",
            "kernel.settings::web",
            "platform.clusters",
            "platform.clusters::web"
        })
package io.github.sudoitir.artemisstudio.feature.diagnostics;

import org.springframework.modulith.ApplicationModule;
