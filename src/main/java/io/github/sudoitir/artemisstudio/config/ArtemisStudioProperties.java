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
 * <p>These are the <em>defaults</em>, not the effective values. Most of this
 * tree is overridable at runtime through {@code studio_setting} — see
 * {@code SettingsService}, which holds the registry of exactly which keys and
 * is the only thing that should be consulted for a live value. A field is
 * read straight off this record only where the registry says it has no key.
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
        Security security,
        Mcp mcp,
        Sse sse) {

    public ArtemisStudioProperties {
        branding = branding != null ? branding : new Branding("Artemis Studio");
        scrape = scrape != null
                ? scrape
                : new Scrape(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(5));
        rateLimit = rateLimit != null ? rateLimit : new RateLimit(20);
        broker = broker != null ? broker : new Broker(Duration.ofSeconds(3), Duration.ofSeconds(10));
        metric = metric != null ? metric : new Metric(7, "0 30 3 * * *", "0 0 3 * * *");
        safety = safety != null ? safety : new Safety(1000);
        events = events != null
                ? events
                : new Events(Duration.ofHours(72), 10_000, Duration.ofSeconds(1), 1000, "0 15 * * * *");
        rr = rr != null
                ? rr
                : new Rr(
                        30_000,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(15),
                        4096,
                        Duration.ofDays(7),
                        "0 20 3 * * *");
        alerting = alerting != null
                ? alerting
                : new Alerting(
                        Duration.ofSeconds(5),
                        5,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(10));
        security = security != null ? security : new Security(Duration.ofHours(8), "groups", null);
        mcp = mcp != null ? mcp : new Mcp(25, 100);
        sse = sse != null ? sse : new Sse(Duration.ofSeconds(20));
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

    /**
     * Raw {@code metric_sample} retention (ADR-0006 — 7-day default). The nightly
     * reaper trims older rows and the maintainer rolls the daily partitions; both
     * crons are runtime-overridable via {@code studio_setting} (ADR-0048).
     */
    public record Metric(
            @DefaultValue("7") int retentionDays,
            @DefaultValue("0 30 3 * * *") String reaperCron,
            @DefaultValue("0 0 3 * * *") String partitionMaintainerCron) {}

    /**
     * Server-enforced ceiling on a single destructive message operation (ADR-0022).
     * A dry-run count above this is a {@code 422} unless the caller passes
     * {@code ?override=true} behind the UI's typed confirmation.
     */
    public record Safety(@DefaultValue("1000") int bulkCap) {}

    /**
     * Broker-event history (ADR-0028). Everything here except
     * {@code coalesceWindowMillis} is overridable at runtime through
     * {@code studio_setting}; the coalescing window is read once by
     * {@code TopicCoalescer} at construction and stays compile-time.
     */
    public record Events(
            @DefaultValue("72h") Duration retention,
            @DefaultValue("10000") int bufferSize,
            @DefaultValue("1s") Duration flush,
            @DefaultValue("1000") int coalesceWindowMillis,
            @DefaultValue("0 15 * * * *") String reaperCron) {}

    /**
     * Request-reply tracing (Phase 5). {@code defaultDeadlineMs} applies only
     * when neither the message nor its expectation carries one. Everything here
     * except {@code percentileWindow} is runtime-overridable (ADR-0047); the
     * percentile window is a Micrometer {@code distributionStatisticExpiry} fixed
     * when the {@code Timer} is registered, so changing it later would silently
     * not apply.
     */
    public record Rr(
            @DefaultValue("30000") int defaultDeadlineMs,
            @DefaultValue("5s") Duration sweepInterval,
            @DefaultValue("5s") Duration sampleInterval,
            @DefaultValue("15m") Duration percentileWindow,
            @DefaultValue("4096") int payloadCaptureBytes,
            @DefaultValue("7d") Duration retention,
            @DefaultValue("0 20 3 * * *") String reaperCron) {}

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
    /**
     * Result caps for the MCP surface (ADR-0045). Deliberately tighter than the
     * REST defaults: a model pays for every row it reads, and the broker pays for
     * every row it did not need. Mutation volume gets no second ceiling here — it
     * inherits {@link Safety#bulkCap()} through {@code MessageService}.
     */
    public record Mcp(
            @DefaultValue("25") int defaultLimit,
            @DefaultValue("100") int maxLimit) {

        /** Clamps a caller-supplied limit into {@code 1..maxLimit}, defaulting a null. */
        public int clamp(Integer requested) {
            if (requested == null) {
                return defaultLimit;
            }
            return Math.clamp(requested, 1, maxLimit);
        }
    }

    public record Security(
            @DefaultValue("8h") Duration sessionTimeout,
            @DefaultValue("groups") String oidcClaim,
            String oidcDefaultRole) {}

    /**
     * The SSE keep-alive comment interval (ADR-0018). It exists as a setting
     * because the value that keeps a stream open is a property of whatever proxy
     * sits in front of Studio, which the operator knows and the image does not.
     */
    public record Sse(@DefaultValue("20s") Duration heartbeatInterval) {}
}
