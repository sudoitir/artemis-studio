/**
 * The replicas of one installation (ADR-0152): who is alive, the Postgres notification bus they
 * talk over, and the readiness and drain behaviour a load balancer relies on.
 */
@ApplicationModule(
        displayName = "Replicas",
        allowedDependencies = {"kernel.core"})
package io.github.sudoitir.artemisstudio.kernel.replica;

import org.springframework.modulith.ApplicationModule;
