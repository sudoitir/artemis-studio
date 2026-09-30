package io.github.sudoitir.artemisstudio.platform.clusters;

import java.util.UUID;

/** This replica became the owner of a cluster (ADR-0148): its broker duties start here. Published locally. */
public record ClusterDutyAcquired(UUID clusterId) {}
