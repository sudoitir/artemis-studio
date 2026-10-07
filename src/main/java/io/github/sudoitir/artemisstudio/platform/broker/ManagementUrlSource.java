package io.github.sudoitir.artemisstudio.platform.broker;

/**
 * Where a node's management URL came from (ADR-0175). Discovery may replace a {@code DERIVED} URL
 * and never a {@code SEED} or {@code MANUAL} one.
 */
public enum ManagementUrlSource {
    /** The operator gave the URL as a seed. */
    SEED,
    /** Built from the cluster's management URL pattern and proved by the broker's NodeID. */
    DERIVED,
    /** The operator set it on the node. */
    MANUAL
}
