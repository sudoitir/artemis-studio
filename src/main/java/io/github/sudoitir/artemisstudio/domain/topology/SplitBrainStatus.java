package io.github.sudoitir.artemisstudio.domain.topology;

/**
 * Whether an HA pair has two nodes claiming to be live, and how sure Studio is
 * (ADR-0012).
 *
 * <ul>
 *   <li>{@link #NONE} — at most one endpoint reports {@code Active=true}.
 *   <li>{@link #SUSPECTED} — two endpoints sharing a NodeID reported
 *       {@code Active=true} in the <em>same</em> refresh cycle, seen once. This is
 *       normal for a few seconds during a failover; Studio does not page on it.
 *   <li>{@link #CRITICAL} — that condition held again on the next consecutive
 *       cycle. Producers may be splitting across both nodes and the journals are
 *       diverging.
 * </ul>
 */
public enum SplitBrainStatus {
    NONE,
    SUSPECTED,
    CRITICAL
}
