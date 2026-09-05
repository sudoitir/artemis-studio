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
        String secretKey,
        Branding branding,
        Scrape scrape,
        RateLimit rateLimit,
        Broker broker,
        Metric metric,
        Safety safety,
        Events events,
        Rr rr,
        Alerting alerting,
        Security security) {

    public ArtemisStudioProperties {
        branding = branding != null ? branding : new Branding("Artemis Studio");
        scrape = scrape != null
                ? scrape
                : new Scrape(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(5));
        rateLimit = rateLimit != null ? rateLimit : new RateLimit(20);
        broker = broker != null ? broker : new Broker(Duration.ofSeconds(3), Duration.ofSeconds(10));
        metric = metric != null ? metric : new Metric(7);
        safety = safety != null ? safety : new Safety(1000);
        events = events != null ? events : new Events(Duration.ofHours(72), 10_000, Duration.ofSeconds(1), 1000);
        rr = rr != null ? rr : new Rr(30_000, Duration.ofSeconds(5), Duration.ofMinutes(15), 4096, Duration.ofDays(7));
        alerting = alerting != null
                ? alerting
                : new Alerting(
                        Duration.ofSeconds(5),
                        5,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(10));
        security = security != null ? security : new Security(Duration.ofHours(8), "groups", null);
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

    /**
     * Server-enforced ceiling on a single destructive message operation (ADR-0022).
     * A dry-run count above this is a {@code 422} unless the caller passes
     * {@code ?override=true} behind the UI's typed confirmation.
     */
    public record Safety(@DefaultValue("1000") int bulkCap) {}

    /**
     * Broker-event history (ADR-0028). {@code retention} and {@code bufferSize}
     * are overridable at runtime through {@code studio_setting}; {@code flush}
     * and {@code coalesceWindow} are compile-time only.
     */
    public record Events(
            @DefaultValue("72h") Duration retention,
            @DefaultValue("10000") int bufferSize,
            @DefaultValue("1s") Duration flush,
            @DefaultValue("1000") int coalesceWindowMillis) {}

    /**
     * Request-reply tracing (Phase 5). {@code defaultDeadlineMs} applies only
     * when neither the message nor its expectation carries one; the sweep and
     * percentile window are compile-time only, matching {@link Events}.
     */
    public record Rr(
            @DefaultValue("30000") int defaultDeadlineMs,
            @DefaultValue("5s") Duration sweepInterval,
            @DefaultValue("15m") Duration percentileWindow,
            @DefaultValue("4096") int payloadCaptureBytes,
            @DefaultValue("7d") Duration retention) {}

    /**
     * Notification delivery (Phase 7, ADR-0036). A separate {@code RestClient}
     * from the broker one — no sharing of the per-node rate limiter or broker
     * TLS bundles with an outbound webhook/Slack call.
     */
    public record Alerting(
            @DefaultValue("5s") Duration dispatchInterval,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("5s") Duration initialBackoff,
            @DefaultValue("10m") Duration maxBackoff) {}

    /**
     * Governance (Phase 8, ADR-0037, ADR-0040). {@code oidcDefaultRole} is the
     * role name granted to an OIDC login matching no
     * {@code oidc_role_mapping} row; {@code null} refuses such a login.
     */
    public record Security(
            @DefaultValue("8h") Duration sessionTimeout,
            @DefaultValue("groups") String oidcClaim,
            String oidcDefaultRole) {}
}
