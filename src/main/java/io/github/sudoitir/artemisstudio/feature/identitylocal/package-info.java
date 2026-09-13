/**
 * Local username and password login.
 */
@ApplicationModule(
        displayName = "Password login",
        allowedDependencies = {"kernel.audit", "kernel.core", "kernel.plugin", "kernel.security"})
package io.github.sudoitir.artemisstudio.feature.identitylocal;

import org.springframework.modulith.ApplicationModule;
