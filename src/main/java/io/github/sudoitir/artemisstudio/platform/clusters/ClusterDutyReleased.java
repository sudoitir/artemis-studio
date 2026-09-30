package io.github.sudoitir.artemisstudio.platform.clusters;

import java.util.UUID;

/**
 * This replica is no longer the owner of a cluster (ADR-0148), whether it handed the cluster over,
 * lost the lease or fenced itself: what it holds for that cluster must be dropped. Published locally.
 */
public record ClusterDutyReleased(UUID clusterId) {}
