/**
 * Getting the audit trail to the plugins that carry it elsewhere: each receiver's cursor, the job
 * that pushes batches to it, and what retention must wait for or leave alone (ADR-0190). The
 * contract a plugin implements is in {@code kernel.audit}.
 */
@ApplicationModule(
        displayName = "Audit delivery",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.plugin :: descriptor",
            "kernel.security",
            "kernel.settings"
        })
package io.github.sudoitir.rahdar.kernel.auditfeed;

import org.springframework.modulith.ApplicationModule;
