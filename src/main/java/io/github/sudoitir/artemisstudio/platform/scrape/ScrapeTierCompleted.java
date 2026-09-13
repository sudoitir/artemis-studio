package io.github.sudoitir.artemisstudio.platform.scrape;

import java.util.UUID;

/**
 * A scrape tier has persisted one cluster's data. Published synchronously on the scrape
 * thread, outside any transaction, before the next cluster is scraped, so a listener reads
 * exactly what the tier just wrote (ADR-0035). A listener must not throw: the scrape carries
 * on regardless.
 *
 * @param tier {@code A} persisted HA state and topology; {@code B} and {@code C} persisted
 *     queue snapshots and metric samples
 */
public record ScrapeTierCompleted(UUID clusterId, Tier tier) {

    public enum Tier {
        A,
        B,
        C
    }
}
