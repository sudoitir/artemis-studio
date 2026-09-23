package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import java.time.Instant;

/** {@link PluginHost#list()}/{@link PluginHost#status(String)}: {@code plugin_install} joined with the live runtime state. */
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
        boolean stuck) {}
