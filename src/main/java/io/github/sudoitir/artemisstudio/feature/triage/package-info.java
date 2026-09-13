/**
 * Cross-feature triage over MCP: cluster and queue diagnosis, and the activity log.
 */
@ApplicationModule(
        displayName = "Triage",
        allowedDependencies = {
            "feature.alerting",
            "feature.alerting :: web",
            "feature.events",
            "feature.events :: web",
            "feature.metrics",
            "feature.metrics :: web",
            "feature.resources",
            "feature.resources :: web",
            "kernel.audit",
            "kernel.audit :: web",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "platform.broker",
            "platform.clusters",
            "platform.clusters :: web",
            "platform.mcp"
        })
package io.github.sudoitir.artemisstudio.feature.triage;

import org.springframework.modulith.ApplicationModule;
