/**
 * Principals, grants, permission checks, users and roles, and the one filter chain.
 */
@ApplicationModule(
        displayName = "Security",
        allowedDependencies = {"kernel.core", "kernel.plugin"})
package io.github.sudoitir.artemisstudio.kernel.security;

import org.springframework.modulith.ApplicationModule;
