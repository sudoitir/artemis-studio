package io.github.sudoitir.artemisstudio.platform.clusters;

import java.time.Instant;
import java.util.UUID;

/** A registered cluster as other modules read it. */
public interface RegisteredCluster {

    UUID getId();

    String getName();

    Instant getCreatedAt();
}
