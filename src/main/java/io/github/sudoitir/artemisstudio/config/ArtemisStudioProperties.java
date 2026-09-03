package io.github.sudoitir.artemisstudio.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code artemis-studio.*} tree from {@code application.yml}.
 *
 * <p>Replaces the previously unread YAML block. The dead
 * {@code jolokia.origin-header} key is gone — Phase 0 proved {@code --relax-jolokia}
 * is on by default in the Artemis image, so no {@code Origin} header is needed.
 *
 * <p>These are the compile-time defaults. From Phase 2 on, the tiers, the
 * per-node rate ceiling and the metric-retention window are overridable at
 * runtime through {@code studio_setting} (see {@code SettingsService}); this
 * record is the fallback the settings layer seeds from.
 */
@ConfigurationProperties(prefix = "artemis-studio")
public record ArtemisStudioProperties(
        String secretKey, Branding branding, Scrape scrape, RateLimit rateLimit, Broker broker, Metric metric) {

    public ArtemisStudioProperties {
        branding = branding != null ? branding : new Branding("Artemis Studio");
        scrape = scrape != null
                ? scrape
                : new Scrape(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(5));
        rateLimit = rateLimit != null ? rateLimit : new RateLimit(20);
        broker = broker != null ? broker : new Broker(Duration.ofSeconds(3), Duration.ofSeconds(10));
        metric = metric != null ? metric : new Metric(7);
    }

    public record Branding(@DefaultValue("Artemis Studio") String productName) {}

    /**
     * Tiered polling cadence (ADR-0015). Tier A is HA + topology corroboration;
     * tier B re-reads the queues that were busy last sweep; tier C walks the
     * whole queue set one page per tick.
     */
    public record Scrape(
            @DefaultValue("5s") Duration tierAInterval,
            @DefaultValue("15s") Duration tierBInterval,
            @DefaultValue("5m") Duration tierCInterval) {}

    public record RateLimit(@DefaultValue("20") int managementCallsPerSecond) {}

    /** {@code RestClient} timeouts applied to every Jolokia call (ADR-0010). */
    public record Broker(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {}

    /** Raw {@code metric_sample} retention (ADR-0006 — 7-day default). The nightly reaper trims older rows. */
    public record Metric(@DefaultValue("7") int retentionDays) {}
}
