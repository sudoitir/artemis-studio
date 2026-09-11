package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerEventReaper;
import io.github.sudoitir.artemisstudio.persist.BrokerEventWriter;
import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.RrFlowReaper;
import io.github.sudoitir.artemisstudio.persist.StudioSettingEntity;
import io.github.sudoitir.artemisstudio.persist.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.web.dto.SettingsViews.SettingValue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The runtime configuration layer: a stored {@code studio_setting} row wins over
 * the packaged {@link ArtemisStudioProperties} default, and deleting the row
 * restores it. Nothing is ever seeded — an absent row <em>means</em> "use the
 * packaged default", which is what lets an upgrade ship a new default instead of
 * being pinned by a value written at first start.
 *
 * <p><b>Every key here applies without a restart.</b> There are two ways that
 * happens, and {@link SettingKey#apply()} is which one:
 *
 * <ul>
 *   <li><b>Pulled</b> ({@code apply == null}) — the consumer calls the getter on
 *       this service each time it needs the value, so the next read is already
 *       the new one. Cheapest, and the default for anything not on a hot path.
 *   <li><b>Pushed</b> ({@code apply != null}) — the consumer holds the value in a
 *       {@code volatile} field because it is read too often to look up, so the
 *       registry pushes it there on boot and after every change.
 * </ul>
 *
 * <p>Cadences are neither: they are {@code SchedulingConfigurer} trigger tasks
 * that re-read this service when computing each next run (ADR-0025, ADR-0048),
 * so a changed interval is honoured on the following fire.
 *
 * <p>To add a setting: one {@code register(...)} line in the constructor. The API, the validation, the
 * reset affordance, the audit row and the Settings screen all follow from it —
 * the screen renders whatever this registry describes (ADR-0047), so there is no
 * matching frontend change to forget.
 */
@Service
@Slf4j
public class SettingsService {

    // ---- keys -------------------------------------------------------------

    public static final String TIER_A = "scrape.tier-a-interval";
    public static final String TIER_B = "scrape.tier-b-interval";
    public static final String TIER_C = "scrape.tier-c-interval";
    public static final String RATE_LIMIT = "rate-limit.calls-per-second";
    public static final String BROKER_CONNECT_TIMEOUT = "broker.connect-timeout";
    public static final String BROKER_READ_TIMEOUT = "broker.read-timeout";
    public static final String RETENTION_DAYS = "metric.retention-days";
    public static final String METRIC_REAPER_CRON = "metric.reaper-cron";
    public static final String METRIC_PARTITION_CRON = "metric.partition-maintainer-cron";
    public static final String BULK_CAP = "safety.bulk-cap";
    public static final String EVENTS_RETENTION_HOURS = "events.retention-hours";
    public static final String EVENTS_BUFFER_SIZE = "events.buffer-size";
    public static final String EVENTS_FLUSH = "events.flush";
    public static final String EVENTS_REAPER_CRON = "events.reaper-cron";
    public static final String RR_DEFAULT_DEADLINE_MS = "rr.default-deadline-ms";
    public static final String RR_PAYLOAD_CAPTURE_BYTES = "rr.payload-capture-bytes";
    public static final String RR_SWEEP_INTERVAL = "rr.sweep-interval";
    public static final String RR_SAMPLE_INTERVAL = "rr.sample-interval";
    public static final String RR_RETENTION_DAYS = "rr.retention-days";
    public static final String RR_REAPER_CRON = "rr.reaper-cron";
    public static final String ALERTING_DISPATCH_INTERVAL = "alerting.dispatch-interval";
    public static final String ALERTING_MAX_ATTEMPTS = "alerting.max-attempts";
    public static final String ALERTING_INITIAL_BACKOFF = "alerting.initial-backoff";
    public static final String ALERTING_MAX_BACKOFF = "alerting.max-backoff";
    public static final String SSE_HEARTBEAT_INTERVAL = "sse.heartbeat-interval";
    public static final String CONFIG_DRIFT_INTERVAL = "config.drift-interval";
    public static final String CONFIG_APPLY_STEP_CAP = "config.apply-step-cap";

    /**
     * How a value is parsed, validated and rendered. The three kinds are the whole
     * vocabulary on purpose: a fourth would mean a new input on the settings
     * screen, which is a design decision and not a config one.
     */
    public enum Kind {
        /** A Spring-style ({@code 5s}, {@code 72h}) or ISO-8601 duration. Must be positive. */
        DURATION,
        /** A whole number, at least 1. */
        INT,
        /** A six-field Spring cron expression. Rejected if it would fire more than once a minute. */
        CRON
    }

    /**
     * One tunable. {@code defaultValue} renders the packaged default rather than
     * capturing it, so the fallback shown in the UI is always the current
     * {@code application.yml} rather than a copy made at construction.
     *
     * @param apply pushes the new value into a consumer that caches it; {@code null}
     *     when the consumer pulls from this service instead
     */
    public record SettingKey(
            String key,
            String group,
            String label,
            String hint,
            Kind kind,
            Supplier<String> defaultValue,
            Runnable apply) {}

    private final StudioSettingRepository repo;
    private final ArtemisStudioProperties defaults;
    private final AuditService audit;
    private final ActorResolver actorResolver;

    /** Insertion-ordered: this is also the order the settings screen renders. */
    private final Map<String, SettingKey> registry = new LinkedHashMap<>();

    /**
     * Every stored override, refreshed on boot and after each write. Reads are on
     * the scheduling hot path — a trigger asks for its interval on every fire —
     * and they used to be a {@code SELECT} each. Writes are the only thing that
     * can invalidate this, and they all go through {@link #applyRuntime()}.
     */
    private volatile Map<String, String> overrides = Map.of();

    @SuppressWarnings("checkstyle:ParameterNumber")
    public SettingsService(
            StudioSettingRepository repo,
            ArtemisStudioProperties defaults,
            AuditService audit,
            ActorResolver actorResolver,
            NodeCallLimiter limiter,
            MetricSampleReaper reaper,
            BrokerEventReaper eventReaper,
            BrokerEventWriter eventWriter,
            RrFlowReaper rrFlowReaper,
            BrokerClientFactory brokerClients,
            RrCorrelator rrCorrelator) {
        this.repo = repo;
        this.defaults = defaults;
        this.audit = audit;
        this.actorResolver = actorResolver;

        // ---- scrape ------------------------------------------------------
        register(
                TIER_A,
                "Scrape",
                "Tier A interval",
                "HA state, topology and split-brain corroboration.",
                Kind.DURATION,
                () -> defaults.scrape().tierAInterval().toString(),
                null);
        register(
                TIER_B,
                "Scrape",
                "Tier B interval",
                "Fast re-read of the queues that were busy last sweep.",
                Kind.DURATION,
                () -> defaults.scrape().tierBInterval().toString(),
                null);
        register(
                TIER_C,
                "Scrape",
                "Tier C interval",
                "Full queue sweep, one page per node per tick.",
                Kind.DURATION,
                () -> defaults.scrape().tierCInterval().toString(),
                null);
        register(
                RATE_LIMIT,
                "Scrape",
                "Per-node call ceiling",
                "Management calls per second, per broker node. Studio must never be the load.",
                Kind.INT,
                () -> Integer.toString(defaults.rateLimit().managementCallsPerSecond()),
                () -> limiter.setPermitsPerSecond(limiterPermits()));

        // ---- broker transport --------------------------------------------
        register(
                BROKER_CONNECT_TIMEOUT,
                "Broker transport",
                "Connect timeout",
                "TCP connect timeout for every Jolokia call. Applies to clients built after the change.",
                Kind.DURATION,
                () -> defaults.broker().connectTimeout().toString(),
                () -> brokerClients.setTimeouts(brokerConnectTimeout(), brokerReadTimeout()));
        register(
                BROKER_READ_TIMEOUT,
                "Broker transport",
                "Read timeout",
                "Response timeout for every Jolokia call. Raise it for a broker with very large queue sets.",
                Kind.DURATION,
                () -> defaults.broker().readTimeout().toString(),
                () -> brokerClients.setTimeouts(brokerConnectTimeout(), brokerReadTimeout()));

        // ---- safety -------------------------------------------------------
        register(
                BULK_CAP,
                "Safety",
                "Bulk operation cap",
                "Most messages one destructive operation may touch before it needs an explicit override.",
                Kind.INT,
                () -> Integer.toString(defaults.safety().bulkCap()),
                null);

        // ---- retention ----------------------------------------------------
        register(
                RETENTION_DAYS,
                "Retention",
                "Metric retention (days)",
                "Raw metric_sample rows older than this are trimmed.",
                Kind.INT,
                () -> Integer.toString(defaults.metric().retentionDays()),
                () -> reaper.setRetentionDays(metricRetentionDays()));
        register(
                METRIC_REAPER_CRON,
                "Retention",
                "Metric reaper schedule",
                "When the metric trim runs. Six-field cron.",
                Kind.CRON,
                () -> defaults.metric().reaperCron(),
                null);
        register(
                METRIC_PARTITION_CRON,
                "Retention",
                "Partition maintainer schedule",
                "When daily metric partitions are created ahead and expired ones dropped.",
                Kind.CRON,
                () -> defaults.metric().partitionMaintainerCron(),
                null);
        register(
                EVENTS_RETENTION_HOURS,
                "Retention",
                "Event retention (hours)",
                "broker_event rows older than this are trimmed.",
                Kind.INT,
                () -> Long.toString(defaults.events().retention().toHours()),
                () -> eventReaper.setRetentionHours(eventsRetentionHours()));
        register(
                EVENTS_REAPER_CRON,
                "Retention",
                "Event reaper schedule",
                "When the broker_event trim runs. Six-field cron.",
                Kind.CRON,
                () -> defaults.events().reaperCron(),
                null);
        register(
                RR_RETENTION_DAYS,
                "Retention",
                "Request-reply retention (days)",
                "rr_flow and rr_event rows older than this are trimmed.",
                Kind.INT,
                () -> Long.toString(defaults.rr().retention().toDays()),
                () -> rrFlowReaper.setRetentionDays(rrRetentionDays()));
        register(
                RR_REAPER_CRON,
                "Retention",
                "Request-reply reaper schedule",
                "When the rr_flow trim runs. Six-field cron.",
                Kind.CRON,
                () -> defaults.rr().reaperCron(),
                null);

        // ---- broker events -------------------------------------------------
        register(
                EVENTS_BUFFER_SIZE,
                "Broker events",
                "Write buffer size",
                "Bounded queue of unwritten events. Overflow is dropped and counted, never blocked on.",
                Kind.INT,
                () -> Integer.toString(defaults.events().bufferSize()),
                () -> eventWriter.setCapacity(eventsBufferSize()));
        register(
                EVENTS_FLUSH,
                "Broker events",
                "Flush interval",
                "How often the buffered events are batch-inserted.",
                Kind.DURATION,
                () -> defaults.events().flush().toString(),
                null);

        // ---- request-reply --------------------------------------------------
        register(
                RR_DEFAULT_DEADLINE_MS,
                "Request-reply",
                "Default deadline (ms)",
                "Used only when neither the message nor its expectation carries a deadline.",
                Kind.INT,
                () -> Integer.toString(defaults.rr().defaultDeadlineMs()),
                () -> rrCorrelator.setDefaultDeadlineMs(rrDefaultDeadlineMs()));
        register(
                RR_PAYLOAD_CAPTURE_BYTES,
                "Request-reply",
                "Payload capture cap (bytes)",
                "How much of a request or reply body is stored when an expectation enables capture.",
                Kind.INT,
                () -> Integer.toString(defaults.rr().payloadCaptureBytes()),
                () -> rrCorrelator.setPayloadCaptureBytes(rrPayloadCaptureBytes()));
        register(
                RR_SWEEP_INTERVAL,
                "Request-reply",
                "Deadline sweep interval",
                "How often flows past their deadline are marked timed out or orphaned.",
                Kind.DURATION,
                () -> defaults.rr().sweepInterval().toString(),
                null);
        register(
                RR_SAMPLE_INTERVAL,
                "Request-reply",
                "Sampler interval",
                "How often enabled expectations are sampled over the Core transport. "
                        + "It is also the error bar on any latency measured by observation.",
                Kind.DURATION,
                () -> defaults.rr().sampleInterval().toString(),
                // The interval is the width of the error bar Studio reports next to an
                // observed latency, so the two must never disagree.
                () -> rrCorrelator.setSampleIntervalMs((int) rrSampleInterval().toMillis()));

        // ---- alerting -------------------------------------------------------
        register(
                ALERTING_DISPATCH_INTERVAL,
                "Alerting",
                "Dispatch interval",
                "How often queued notification deliveries are claimed and attempted.",
                Kind.DURATION,
                () -> defaults.alerting().dispatchInterval().toString(),
                null);
        register(
                ALERTING_MAX_ATTEMPTS,
                "Alerting",
                "Max delivery attempts",
                "A delivery is marked dead after this many failures.",
                Kind.INT,
                () -> Integer.toString(defaults.alerting().maxAttempts()),
                null);
        register(
                ALERTING_INITIAL_BACKOFF,
                "Alerting",
                "Initial retry backoff",
                "Delay before the first retry. Doubles with jitter up to the ceiling.",
                Kind.DURATION,
                () -> defaults.alerting().initialBackoff().toString(),
                null);
        register(
                ALERTING_MAX_BACKOFF,
                "Alerting",
                "Max retry backoff",
                "Ceiling on the exponential retry delay.",
                Kind.DURATION,
                () -> defaults.alerting().maxBackoff().toString(),
                null);

        // ---- stream ---------------------------------------------------------
        register(
                SSE_HEARTBEAT_INTERVAL,
                "Stream",
                "SSE heartbeat interval",
                "Keep-alive comment on GET /api/v1/stream. Lower it if a proxy idles the connection out sooner.",
                Kind.DURATION,
                () -> defaults.sse().heartbeatInterval().toString(),
                null);

        // ---- broker configuration -------------------------------------------
        register(
                CONFIG_DRIFT_INTERVAL,
                "Broker configuration",
                "Drift evaluation interval",
                "How often every live node is compared against its cluster's declared configuration."
                        + " One batched read per node per pass; nothing is ever applied by it.",
                Kind.DURATION,
                () -> defaults.brokerConfig().driftInterval().toString(),
                null);
        register(
                CONFIG_APPLY_STEP_CAP,
                "Broker configuration",
                "Apply step cap",
                "Most management writes one configuration apply may issue before it needs an explicit override.",
                Kind.INT,
                () -> Integer.toString(defaults.brokerConfig().applyStepCap()),
                null);
    }

    private void register(
            String key,
            String group,
            String label,
            String hint,
            Kind kind,
            Supplier<String> defaultValue,
            Runnable apply) {
        registry.put(key, new SettingKey(key, group, label, hint, kind, defaultValue, apply));
    }

    // ---- typed getters ----------------------------------------------------

    public Duration tierA() {
        return duration(TIER_A);
    }

    public Duration tierB() {
        return duration(TIER_B);
    }

    public Duration tierC() {
        return duration(TIER_C);
    }

    public int limiterPermits() {
        return intValue(RATE_LIMIT);
    }

    public Duration brokerConnectTimeout() {
        return duration(BROKER_CONNECT_TIMEOUT);
    }

    public Duration brokerReadTimeout() {
        return duration(BROKER_READ_TIMEOUT);
    }

    public int metricRetentionDays() {
        return intValue(RETENTION_DAYS);
    }

    public String metricReaperCron() {
        return effectiveValue(METRIC_REAPER_CRON);
    }

    public String metricPartitionCron() {
        return effectiveValue(METRIC_PARTITION_CRON);
    }

    /** Server-enforced ceiling on one destructive message operation (ADR-0022). */
    public int bulkCap() {
        return intValue(BULK_CAP);
    }

    public int eventsRetentionHours() {
        return intValue(EVENTS_RETENTION_HOURS);
    }

    public int eventsBufferSize() {
        return intValue(EVENTS_BUFFER_SIZE);
    }

    public Duration eventsFlush() {
        return duration(EVENTS_FLUSH);
    }

    public String eventsReaperCron() {
        return effectiveValue(EVENTS_REAPER_CRON);
    }

    public int rrDefaultDeadlineMs() {
        return intValue(RR_DEFAULT_DEADLINE_MS);
    }

    public int rrPayloadCaptureBytes() {
        return intValue(RR_PAYLOAD_CAPTURE_BYTES);
    }

    public Duration rrSweepInterval() {
        return duration(RR_SWEEP_INTERVAL);
    }

    public Duration rrSampleInterval() {
        return duration(RR_SAMPLE_INTERVAL);
    }

    public int rrRetentionDays() {
        return intValue(RR_RETENTION_DAYS);
    }

    public String rrReaperCron() {
        return effectiveValue(RR_REAPER_CRON);
    }

    public Duration alertingDispatchInterval() {
        return duration(ALERTING_DISPATCH_INTERVAL);
    }

    public int alertingMaxAttempts() {
        return intValue(ALERTING_MAX_ATTEMPTS);
    }

    public Duration alertingInitialBackoff() {
        return duration(ALERTING_INITIAL_BACKOFF);
    }

    public Duration alertingMaxBackoff() {
        return duration(ALERTING_MAX_BACKOFF);
    }

    public Duration sseHeartbeatInterval() {
        return duration(SSE_HEARTBEAT_INTERVAL);
    }

    public Duration configDriftInterval() {
        return duration(CONFIG_DRIFT_INTERVAL);
    }

    public int configApplyStepCap() {
        return intValue(CONFIG_APPLY_STEP_CAP);
    }

    // ---- read / write -----------------------------------------------------

    /** Every operator-tunable key: its effective value, its default, and how to render it. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).SETTINGS_READ)")
    public Map<String, SettingValue> effective() {
        Map<String, String> stored = overrides;
        Map<String, SettingValue> out = new LinkedHashMap<>();
        registry.forEach((key, spec) -> {
            String defaultValue = spec.defaultValue().get();
            String override = stored.get(key);
            out.put(
                    key,
                    new SettingValue(
                            override != null ? override : defaultValue,
                            override != null,
                            defaultValue,
                            spec.group(),
                            spec.label(),
                            spec.hint(),
                            spec.kind().name()));
        });
        return out;
    }

    /**
     * Audited in the same transaction as the write (non-negotiable #3), so a change
     * and the record of it commit or roll back together.
     *
     * <p>Validation happens <em>before</em> the audit row rather than after: a
     * rejected value never becomes a transaction, so a {@code begin}/{@code fail}
     * pair around it would roll back with everything else and record nothing. An
     * unparseable duration is a {@code 400} on the request, not an event in the
     * history of what this deployment's configuration has been.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).SETTINGS_WRITE)")
    @Transactional
    public void put(String key, String rawValue) {
        SettingKey spec = requireKnown(key);
        String value = unquote(rawValue);
        validate(spec, value);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_SETTING",
                "SETTING",
                key,
                null,
                null,
                Map.of("from", overrides.getOrDefault(key, spec.defaultValue().get()), "to", value),
                false);

        String json = asJsonScalar(value);
        repo.findById(key).ifPresentOrElse(e -> e.setValue(json), () -> repo.save(new StudioSettingEntity(key, json)));
        repo.flush();
        applyRuntime();
        audit.succeed(event, 1);
    }

    /** Clears the override so the packaged default takes over again. Audited like {@link #put}. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).SETTINGS_WRITE)")
    @Transactional
    public void reset(String key) {
        SettingKey spec = requireKnown(key);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "RESET_SETTING",
                "SETTING",
                key,
                null,
                null,
                Map.of(
                        "from", overrides.getOrDefault(key, spec.defaultValue().get()),
                        "to", spec.defaultValue().get()),
                false);

        repo.deleteById(key);
        repo.flush();
        applyRuntime();
        audit.succeed(event, 1);
    }

    /**
     * Re-read the stored overrides and push the cached ones to their holders. Runs
     * on boot and after every change; the pulled keys need nothing, and cadences
     * pick the new value up on their next fire.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void applyRuntime() {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (StudioSettingEntity row : repo.findAll()) {
            if (registry.containsKey(row.getKey())) {
                fresh.put(row.getKey(), unquote(row.getValue()));
            }
        }
        overrides = Map.copyOf(fresh);
        for (SettingKey spec : registry.values()) {
            if (spec.apply() != null) {
                spec.apply().run();
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private String effectiveValue(String key) {
        String override = overrides.get(key);
        return override != null ? override : requireKnown(key).defaultValue().get();
    }

    private Duration duration(String key) {
        return Duration.parse(toIso(effectiveValue(key)));
    }

    private int intValue(String key) {
        return Integer.parseInt(effectiveValue(key).trim());
    }

    private SettingKey requireKnown(String key) {
        SettingKey spec = registry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown setting key: " + key);
        }
        return spec;
    }

    private static void validate(SettingKey spec, String value) {
        switch (spec.kind()) {
            case DURATION -> {
                Duration d = Duration.parse(toIso(value));
                if (d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException(spec.key() + " must be a positive duration");
                }
            }
            case INT -> {
                int n = Integer.parseInt(value.trim());
                if (n < 1) {
                    throw new IllegalArgumentException(spec.key() + " must be at least 1");
                }
            }
            case CRON -> validateCron(spec.key(), value.trim());
        }
    }

    /**
     * A cron that fires more often than once a minute is rejected rather than
     * clamped. These schedules drive bulk {@code DELETE}s and DDL; a typo in the
     * seconds field would otherwise turn a nightly trim into a permanent one, and
     * silently accepting it is how that becomes a production incident.
     */
    private static void validateCron(String key, String value) {
        if (!CronExpression.isValidExpression(value)) {
            throw new IllegalArgumentException(key + " must be a six-field cron expression");
        }
        CronExpression cron = CronExpression.parse(value);
        java.time.LocalDateTime from = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);
        java.time.LocalDateTime first = cron.next(from);
        java.time.LocalDateTime second = first == null ? null : cron.next(first);
        if (first != null
                && second != null
                && java.time.Duration.between(first, second).toSeconds() < 60) {
            throw new IllegalArgumentException(key + " must not fire more than once a minute");
        }
    }

    /** Accept both {@code "5s"} (Spring style) and {@code "PT5S"} (ISO-8601) duration strings. */
    private static String toIso(String value) {
        String v = value.trim();
        if (v.startsWith("P") || v.startsWith("p")) {
            return v.toUpperCase();
        }
        if (v.endsWith("ms")) {
            return "PT" + (Long.parseLong(v.substring(0, v.length() - 2).trim()) / 1000.0) + "S";
        }
        if (v.endsWith("s")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "S";
        }
        if (v.endsWith("m")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "M";
        }
        if (v.endsWith("h")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "H";
        }
        if (v.endsWith("d")) {
            return "P" + v.substring(0, v.length() - 1).trim() + "D";
        }
        return "PT" + v + "S";
    }

    /** Encode a bare value as a JSON scalar for the {@code jsonb} column: a number stays bare, anything else is quoted. */
    private static String asJsonScalar(String value) {
        String v = value.trim();
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            return v;
        }
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    /** Stored values are JSON scalars; strip the quotes from a JSON string. */
    private static String unquote(String jsonScalar) {
        String v = jsonScalar.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        return v;
    }
}
