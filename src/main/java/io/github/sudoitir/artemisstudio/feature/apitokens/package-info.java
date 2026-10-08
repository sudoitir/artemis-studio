/**
 * Bearer tokens for automation and MCP clients.
 */
@ApplicationModule(
        displayName = "API tokens",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security",
            "kernel.settings",
            "platform.clusters"
        })
package io.github.sudoitir.artemisstudio.feature.apitokens;

import org.springframework.modulith.ApplicationModule;
