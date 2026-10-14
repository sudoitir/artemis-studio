/**
 * OIDC login and claim-to-role mapping.
 */
@ApplicationModule(
        displayName = "Single sign-on",
        allowedDependencies = {"kernel.plugin", "kernel.security"})
package io.github.sudoitir.rahdar.feature.identityoidc;

import org.springframework.modulith.ApplicationModule;
