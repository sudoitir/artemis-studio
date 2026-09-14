/**
 * The content policy (ADR-0075): masking rules, PII detectors, the classification inbox, sealing
 * of stored originals and re-masking on policy change. Every path that emits or stores message
 * content governs it here first.
 */
@ApplicationModule(
        displayName = "Data governance",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings"
        })
package io.github.sudoitir.artemisstudio.platform.governance;

import org.springframework.modulith.ApplicationModule;
