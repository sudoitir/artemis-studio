package io.github.sudoitir.artemisstudio.kernel.core;

/**
 * A result of {@link ReadRouting#read} with where it was read.
 *
 * @param value what the read returned
 * @param fromReplica whether it came from the read replica, which may lag the primary; a response
 *     built from it says so
 */
public record Routed<T>(T value, boolean fromReplica) {}
