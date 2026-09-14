package io.github.sudoitir.artemisstudio.platform.clusters;

import java.util.UUID;

/**
 * A cluster has been registered and its topology discovered. Published synchronously inside
 * the registration transaction, so a listener's writes commit or roll back with the
 * registration.
 */
public record ClusterRegistered(UUID clusterId) {}
