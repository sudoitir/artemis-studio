/**
 * Cross-broker message transfer: move or copy messages from a queue on one node to a queue on
 * another node of the same or another cluster, staged and deduplicated so an interruption neither
 * loses nor doubles a message (ADR-0097).
 */
@ApplicationModule(
        displayName = "Message transfer",
        allowedDependencies = {
            "feature.messages",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.scrape"
        })
package io.github.sudoitir.artemisstudio.feature.transfer;

import org.springframework.modulith.ApplicationModule;
