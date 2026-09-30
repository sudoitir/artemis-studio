package io.github.sudoitir.artemisstudio.kernel.replica;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What crosses the bus between replicas (ADR-0152). The wire names the shape, never a class, so a
 * rolling upgrade can rename a type without breaking its neighbours.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BusFrame.class, name = "frame"),
    @JsonSubTypes.Type(value = BusEvents.class, name = "events"),
    @JsonSubTypes.Type(value = ReplicaSignal.class, name = "signal")
})
public sealed interface BusMessage permits BusFrame, BusEvents, ReplicaSignal {}
