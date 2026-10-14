package io.github.sudoitir.rahdar.feature.setupreview;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Setup review cadence (ADR-0106).
 *
 * @param interval how often every cluster is reviewed — one batched read per node each time
 * @param minInterval the least spacing between two reviews of one cluster, on demand included
 */
@ConfigurationProperties(prefix = "rahdar.setup-review")
public record SetupReviewProperties(
        @DefaultValue("15m") Duration interval,
        @DefaultValue("30s") Duration minInterval) {}
