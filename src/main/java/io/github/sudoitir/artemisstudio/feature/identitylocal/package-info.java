/**
 * Local username and password login.
 */
@ApplicationModule(
        displayName = "Password login",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings"
        })
package io.github.sudoitir.artemisstudio.feature.identitylocal;

import org.springframework.modulith.ApplicationModule;
