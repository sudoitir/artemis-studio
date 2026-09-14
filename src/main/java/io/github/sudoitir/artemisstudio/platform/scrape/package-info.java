/**
 * Tiered polling of every node, and the queue snapshot and metric sample read APIs.
 */
@ApplicationModule(
        displayName = "Scraping",
        allowedDependencies = {
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters"
        })
package io.github.sudoitir.artemisstudio.platform.scrape;

import org.springframework.modulith.ApplicationModule;
