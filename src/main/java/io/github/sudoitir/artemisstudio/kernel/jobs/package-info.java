/**
 * The one scheduler for module jobs, with per-job status and timing.
 */
@ApplicationModule(
        displayName = "Scheduled jobs",
        allowedDependencies = {"kernel.core", "kernel.security"})
package io.github.sudoitir.artemisstudio.kernel.jobs;

import org.springframework.modulith.ApplicationModule;
