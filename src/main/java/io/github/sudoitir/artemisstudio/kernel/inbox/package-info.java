/**
 * The in-app inbox: notices that Studio features and plugins post to users, kept per recipient and
 * retained under the data lifecycle. Live delivery is {@code kernel.stream}'s user stream.
 */
@ApplicationModule(
        displayName = "Inbox",
        allowedDependencies = {
            "kernel.core",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream"
        })
package io.github.sudoitir.artemisstudio.kernel.inbox;

import org.springframework.modulith.ApplicationModule;
