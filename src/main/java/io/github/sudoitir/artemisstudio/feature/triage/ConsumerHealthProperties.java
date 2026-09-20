package io.github.sudoitir.artemisstudio.feature.triage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The three numbers the consumer-health ladder compares against (ADR-0089).
 *
 * <p>The ladder itself is fixed — its value is a shared vocabulary, and site-specific
 * predicates would destroy that. These are where real variation between installations
 * lives, so they are the part that is configurable.
 */
@ConfigurationProperties(prefix = "artemis-studio.consumer-health")
public record ConsumerHealthProperties(
        /**
         * An acknowledgement rate at or below this counts as "not acknowledging" (msg/s).
         * Not zero: a queue draining one message every few minutes is stalled in every
         * sense an operator cares about, and floating-point rates rarely land on exactly 0.
         */
        @DefaultValue("0.01") double idleAckRate,

        /**
         * The depth below which a queue is idle rather than backed up. A queue with no
         * backlog and no acknowledgements is simply quiet, not stalled.
         */
        @DefaultValue("1") long minBacklog,

        /**
         * How far back to look for a broker {@code CONSUMER_SLOW} notification before
         * treating it as history rather than the current state.
         */
        @DefaultValue("10m") java.time.Duration brokerSlowWindow) {}
