/**
 * Bearer tokens for automation and MCP clients.
 */
@ApplicationModule(
        displayName = "API tokens",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "platform.clusters"
        })
package io.github.sudoitir.artemisstudio.feature.apitokens;

import org.springframework.modulith.ApplicationModule;
