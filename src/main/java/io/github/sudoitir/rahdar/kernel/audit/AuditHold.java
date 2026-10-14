package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit records retention must leave alone: those within the time range that also match every
 * filter that is set. A null filter matches anything, a null {@code from} or {@code to} leaves
 * the range open on that side.
 */
@PluginApi
public record AuditHold(
        Instant from,
        Instant to,
        UUID clusterId,
        String username,
        String action,
        String targetType,
        String targetName) {}
