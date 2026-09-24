package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.time.Instant;

/**
 * {@link PluginHost#list()}/{@link PluginHost#status(String)}: {@code plugin_install} joined with the live runtime state.
 *
 * @param rollbackAvailable a previous version is on record and the current one changed no schema
 * @param descriptor the installed version's descriptor; {@code null} only when the stored copy is unreadable
 */
public record PluginSummary(
        String id,
        String version,
        String vendor,
        String sha256,
        String previousSha256,
        PluginInstallStatus status,
        String failure,
        String progress,
        Instant stepStartedAt,
        Instant installedAt,
        Instant activatedAt,
        String installedBy,
        boolean stuck,
        boolean rollbackAvailable,
        PluginDescriptor descriptor) {}
