package io.github.sudoitir.artemisstudio.platform.governance;

/**
 * SPI: a module that stores governed message content re-masks its own rows when the policy changes (ADR-0075
 * D5). The tables belong to their modules, so the platform drives the work and the owner does it.
 */
public interface StoredContentRemasker {

    /** A stable name, reported with progress. */
    String name();

    /** Rows stored under a policy version below {@code version}, counted up to {@code cap}. */
    long countBelow(int version, long cap);

    /**
     * Re-mask up to {@code limit} rows stored under a version below {@code version}, sealing any newly masked
     * originals. Returns how many rows were rewritten; fewer than {@code limit} means none are left.
     */
    int remask(int version, int limit);
}
