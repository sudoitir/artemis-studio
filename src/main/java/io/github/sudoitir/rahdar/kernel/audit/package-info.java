/**
 * The audit trail every mutating action writes, and its query.
 */
@ApplicationModule(
        displayName = "Audit log",
        allowedDependencies = {"kernel.plugin", "kernel.security"})
package io.github.sudoitir.rahdar.kernel.audit;

import org.springframework.modulith.ApplicationModule;
