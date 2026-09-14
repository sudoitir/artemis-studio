package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetRegistry.ClockOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The registered broker nodes, as the transport needs to see them. Implemented by the module
 * that owns node registration, so the transport never reads that module's tables.
 */
public interface NodeDirectory {

    /** Every registered node. */
    List<KnownNode> nodes();

    /** Persist the latest clock reading for each node that has one (ADR-0053). */
    void recordClockOffsets(Map<UUID, ClockOffset> byNode);

    /** One registered node. {@code jolokiaUrl} is {@code null} for a node Studio cannot manage yet. */
    record KnownNode(UUID id, UUID clusterId, String name, String jolokiaUrl) {}
}
