package io.github.sudoitir.artemisstudio.kernel.replica;

/**
 * Published locally, first thing at shutdown, while this replica still serves: whatever it owns
 * should be handed over now rather than when the process ends.
 */
public record DrainStarted() {}
