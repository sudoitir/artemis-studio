package io.github.sudoitir.artemisstudio.platform.clusters;

import java.util.UUID;

/**
 * An environment has been deleted. Published synchronously inside the deleting
 * transaction, so a listener's clean-up commits or rolls back with it.
 */
public record EnvironmentRemoved(UUID environmentId) {}
