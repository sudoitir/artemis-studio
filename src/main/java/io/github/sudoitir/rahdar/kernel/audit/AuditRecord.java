package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.UUID;

/**
 * One audit event as stored. {@code params} and {@code outcomeDetail} are JSON text, null when
 * absent; {@code position} is where the event sits in commit order at the time of the read.
 */
@PluginApi
public record AuditRecord(
        long id,
        Instant ts,
        String action,
        String targetType,
        String targetName,
        UUID clusterId,
        String clusterName,
        UUID userId,
        String username,
        String outcome,
        String error,
        Long affectedCount,
        String requestId,
        String params,
        String sourceIp,
        UUID nodeId,
        boolean dryRun,
        String outcomeDetail,
        Long parentId,
        AuditPosition position) {}
