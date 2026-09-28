package io.github.sudoitir.artemisstudio.platform.scrape;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tiered polling cadence (ADR-0015). Tier A is HA + topology corroboration; tier B
 * re-reads the queues that were busy last sweep; tier C walks the whole queue set one
 * page per tick; discovery re-reads each cluster's topology to find brokers that joined
 * (ADR-0119). These are defaults; the live values are runtime settings.
 */
@ConfigurationProperties(prefix = "artemis-studio.scrape")
public record ScrapeProperties(
        @DefaultValue("5s") Duration tierAInterval,
        @DefaultValue("15s") Duration tierBInterval,
        @DefaultValue("5m") Duration tierCInterval,
        @DefaultValue("1m") Duration discoveryInterval) {}
