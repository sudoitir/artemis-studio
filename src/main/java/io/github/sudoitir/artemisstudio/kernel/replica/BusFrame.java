package io.github.sudoitir.artemisstudio.kernel.replica;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A stream frame for one cluster's topic. {@code data} is null for a signal-only frame, which is also
 * what a frame too large for the bus becomes; {@code id} is the SSE id, null for a frame with none.
 * Nulls are left off the wire, so they arrive as nulls. Published as a Spring event on every replica
 * when it arrives.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusFrame(UUID clusterId, String topic, JsonNode data, String id) implements BusMessage {}
