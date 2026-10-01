package io.github.sudoitir.artemisstudio.kernel.replica;

/**
 * Published locally when the bus connection came back after a loss. Messages sent in the gap are
 * gone, so a listener drops what it cached and reloads.
 */
public record BusResumed() {}
