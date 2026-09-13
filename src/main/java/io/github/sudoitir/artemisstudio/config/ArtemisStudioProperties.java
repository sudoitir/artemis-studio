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
        Sql sql,
        Capture capture,
        Sse sse,
        BrokerConfig brokerConfig) {

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
                        "0 20 3 * * *",
                        2_000);
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
        sql = sql != null
                ? sql
                : new Sql(
                        50,
                        50_000L,
                        2_000,
                        250_000L,
                        Duration.ofSeconds(30),
                        2,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1));
        capture = capture != null ? capture : new Capture("amq", Duration.ofSeconds(30), Duration.ofHours(24));
        sse = sse != null ? sse : new Sse(Duration.ofSeconds(20));
        brokerConfig = brokerConfig != null ? brokerConfig : new BrokerConfig(Duration.ofMinutes(5), 100);
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
     * The SQL Console's bounds (ADR-0058 D6). Every one of these exists so that a
     * query is a bounded amount of broker load, and every one of them is reported
     * when it is reached — a bounded result that does not say it is bounded reads as
     * a complete one.
     *
     * @param maxTargets how many queues one query may fan out to
     * @param scanCap how many messages one query may examine before it stops
     * @param maxRows how many rows one query may return
     * @param costCeiling the estimate above which a query is refused rather than started
     * @param timeout wall clock for one query, after which it returns what it has
     * @param maxConcurrentQueries how many queries one operator may have running
     * @param tailInterval how often a live tail re-reads its targets
     * @param minTailInterval the floor on {@code tailInterval}, so a tail cannot be
     *     turned into a load generator
     */
    public record Sql(
            @DefaultValue("50") int maxTargets,
            @DefaultValue("50000") long scanCap,
            @DefaultValue("2000") int maxRows,
            @DefaultValue("250000") long costCeiling,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("2") int maxConcurrentQueries,
            @DefaultValue("5s") Duration tailInterval,
            @DefaultValue("1s") Duration minTailInterval) {

        public Sql {
            if (tailInterval.compareTo(minTailInterval) < 0) {
                tailInterval = minTailInterval;
            }
        }
    }

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
            @DefaultValue("0 20 3 * * *") String reaperCron,
            /**
             * How far a clock may disagree with Studio's before it is called skew.
             *
             * <p>The floor is set by the measurement, not by taste: Jolokia reports
             * its timestamp in whole seconds, so a single reading is ±500ms before
             * the network is counted. Two seconds is the smallest value that is not
             * mostly quantisation (ADR-0053).
             */
            @DefaultValue("2000") long clockSkewToleranceMs) {}

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
    /**
     * Divert-based message capture (ADR-0062).
     *
     * @param brokerRole the broker role Studio's own connection holds. It is what the
     *     capture queue's {@code security-setting} grants consume to, and Studio cannot
     *     discover it — the broker exposes no "who am I" read — so it is stated here and
     *     verified by whether the consumer can actually attach.
     * @param reconcileInterval how often desired and actual capture state are converged.
     *     Not a per-queue poll: one pass reads each live node's divert names once.
     * @param expiry how long a message may sit in a capture queue before the broker drops
     *     it. With {@code auto-create-expiry-resources=false} this bounds an abandoned tap
     *     in age, which — since the divert survives a restart (ADR-0065) — is the half of
     *     the bound that {@code ring-size} cannot provide.
     */
    public record Capture(
            @DefaultValue("amq") String brokerRole,
            @DefaultValue("30s") Duration reconcileInterval,
            @DefaultValue("24h") Duration expiry) {}

    public record Sse(@DefaultValue("20s") Duration heartbeatInterval) {}

    /**
     * Declared broker configuration (ADR-0067). {@code driftInterval} paces the
     * scheduled comparison of every live node against its cluster's declaration —
     * one batched read per node per pass; {@code applyStepCap} is the most management
     * writes one apply may issue before it needs an explicit override, the
     * configuration counterpart of the bulk cap.
     */
    public record BrokerConfig(
            @DefaultValue("5m") Duration driftInterval,
            @DefaultValue("100") int applyStepCap) {}
}
