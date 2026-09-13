package io.github.sudoitir.artemisstudio.feature.sql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The SQL Console's bounds (ADR-0058 D6). Every one of these exists so that a query is a
 * bounded amount of broker load, and every one of them is reported when it is reached — a
 * bounded result that does not say it is bounded reads as a complete one.
 *
 * @param maxTargets how many queues one query may fan out to
 * @param scanCap how many messages one query may examine before it stops
 * @param maxRows how many rows one query may return
 * @param costCeiling the estimate above which a query is refused rather than started
 * @param timeout wall clock for one query, after which it returns what it has
 * @param maxConcurrentQueries how many queries one operator may have running
 * @param tailInterval how often a live tail re-reads its targets
 * @param minTailInterval the floor on {@code tailInterval}, so a tail cannot be turned into
 *     a load generator
 */
@ConfigurationProperties(prefix = "artemis-studio.sql")
public record SqlProperties(
        @DefaultValue("50") int maxTargets,
        @DefaultValue("50000") long scanCap,
        @DefaultValue("2000") int maxRows,
        @DefaultValue("250000") long costCeiling,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("2") int maxConcurrentQueries,
        @DefaultValue("5s") Duration tailInterval,
        @DefaultValue("1s") Duration minTailInterval) {

    public SqlProperties {
        if (tailInterval.compareTo(minTailInterval) < 0) {
            tailInterval = minTailInterval;
        }
    }
}
