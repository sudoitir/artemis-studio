package io.github.sudoitir.artemisstudio.persist;

/**
 * What capture is doing on one node (ADR-0062 D4). Per node, because a tap is a
 * node-local object and broker authorisation is a node-local fact.
 */
public enum CaptureState {
    /** The subscription exists; the tap has not been installed on this node yet. */
    PENDING,

    /** The tap is installed and being drained. */
    ACTIVE,

    /** Installed and draining, but losing messages — the ring dropped, or a bound was reached. */
    DEGRADED,

    /** Not installed, and the next pass will not fix it on its own. The detail says why. */
    FAILED
}
