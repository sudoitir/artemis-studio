/**
 * Cross-feature triage: cluster and queue diagnosis, consumer health, and the activity
 * log, over REST and MCP alike.
 *
 * <p>{@code platform.scrape} is depended on for the metric-sample reads the consumer-health
 * verdict is derived from (ADR-0089) — the same platform module {@code feature.metrics}
 * and {@code feature.alerting} already read.
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
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.triage;

import org.springframework.modulith.ApplicationModule;
