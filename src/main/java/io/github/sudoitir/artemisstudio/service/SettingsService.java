package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.BrokerEventReaper;
import io.github.sudoitir.artemisstudio.persist.BrokerEventWriter;
import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.StudioSettingEntity;
import io.github.sudoitir.artemisstudio.persist.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.web.dto.SettingsViews.SettingValue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The runtime configuration layer: a stored {@code studio_setting} override wins
 * over the compile-time {@link ArtemisStudioProperties} default. The scrape
 * cadences are read here at scheduler wiring time (SpEL {@code #{@settingsService…}}),
 * and the limiter ceiling / metric-retention window are pushed to their live
 * holders whenever a setting changes.
 *
 * <p>Cadence changes take effect on restart (the {@code @Scheduled} fixed delays
 * are resolved once); the limiter and reaper apply immediately. A
 * {@code SchedulingConfigurer} with a re-reading trigger would make cadence live
 * too — filed as a fast-follow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    public static final String TIER_A = "scrape.tier-a-interval";
    public static final String TIER_B = "scrape.tier-b-interval";
    public static final String TIER_C = "scrape.tier-c-interval";
    public static final String RATE_LIMIT = "rate-limit.calls-per-second";
    public static final String RETENTION_DAYS = "metric.retention-days";
    public static final String BULK_CAP = "safety.bulk-cap";
    public static final String EVENTS_RETENTION_HOURS = "events.retention-hours";
    public static final String EVENTS_BUFFER_SIZE = "events.buffer-size";

    private final StudioSettingRepository repo;
    private final ArtemisStudioProperties defaults;
    private final NodeCallLimiter limiter;
    private final MetricSampleReaper reaper;
    private final BrokerEventReaper eventReaper;
    private final BrokerEventWriter eventWriter;

    // ---- typed getters (defaults from application.yml) --------------------

    public Duration tierA() {
        return duration(TIER_A, defaults.scrape().tierAInterval());
    }

    public Duration tierB() {
        return duration(TIER_B, defaults.scrape().tierBInterval());
    }

    public Duration tierC() {
        return duration(TIER_C, defaults.scrape().tierCInterval());
    }

    /** SpEL entry points for {@code @Scheduled(fixedDelayString = "#{@settingsService.tierAMillis()}")}. */
    public long tierAMillis() {
        return tierA().toMillis();
    }

    public long tierBMillis() {
        return tierB().toMillis();
    }

    public long tierCMillis() {
        return tierC().toMillis();
    }

    public int limiterPermits() {
        return intValue(RATE_LIMIT, defaults.rateLimit().managementCallsPerSecond());
    }

    public int metricRetentionDays() {
        return intValue(RETENTION_DAYS, defaults.metric().retentionDays());
    }

    /** Server-enforced ceiling on one destructive message operation (ADR-0022). */
    public int bulkCap() {
        return intValue(BULK_CAP, defaults.safety().bulkCap());
    }

    public int eventsRetentionHours() {
        return intValue(
                EVENTS_RETENTION_HOURS, (int) defaults.events().retention().toHours());
    }

    public int eventsBufferSize() {
        return intValue(EVENTS_BUFFER_SIZE, defaults.events().bufferSize());
    }

    // ---- read / write -----------------------------------------------------

    /** Every operator-tunable key: its effective value and whether it is a stored override. */
    @Transactional(readOnly = true)
    public Map<String, SettingValue> effective() {
        Map<String, SettingValue> out = new LinkedHashMap<>();
        out.put(TIER_A, entry(TIER_A, defaults.scrape().tierAInterval().toString()));
        out.put(TIER_B, entry(TIER_B, defaults.scrape().tierBInterval().toString()));
        out.put(TIER_C, entry(TIER_C, defaults.scrape().tierCInterval().toString()));
        out.put(
                RATE_LIMIT,
                entry(RATE_LIMIT, Integer.toString(defaults.rateLimit().managementCallsPerSecond())));
        out.put(
                RETENTION_DAYS,
                entry(RETENTION_DAYS, Integer.toString(defaults.metric().retentionDays())));
        out.put(BULK_CAP, entry(BULK_CAP, Integer.toString(defaults.safety().bulkCap())));
        out.put(
                EVENTS_RETENTION_HOURS,
                entry(
                        EVENTS_RETENTION_HOURS,
                        Long.toString(defaults.events().retention().toHours())));
        out.put(
                EVENTS_BUFFER_SIZE,
                entry(EVENTS_BUFFER_SIZE, Integer.toString(defaults.events().bufferSize())));
        return out;
    }

    @Transactional
    public void put(String key, String rawValue) {
        validate(key, rawValue);
        String json = asJsonScalar(unquote(rawValue));
        repo.findById(key).ifPresentOrElse(e -> e.setValue(json), () -> repo.save(new StudioSettingEntity(key, json)));
        applyRuntime();
    }

    @Transactional
    public void reset(String key) {
        requireKnown(key);
        repo.deleteById(key);
        applyRuntime();
    }

    /** Push the live-adjustable settings to their holders — on boot and after every change. */
    @EventListener(ApplicationReadyEvent.class)
    public void applyRuntime() {
        limiter.setPermitsPerSecond(limiterPermits());
        reaper.setRetentionDays(metricRetentionDays());
        eventReaper.setRetentionHours(eventsRetentionHours());
        eventWriter.setCapacity(eventsBufferSize());
    }

    // ---- helpers --------------------------------------------------------

    private SettingValue entry(String key, String defaultValue) {
        return repo.findById(key)
                .map(e -> new SettingValue(unquote(e.getValue()), true, defaultValue))
                .orElseGet(() -> new SettingValue(defaultValue, false, defaultValue));
    }

    private Duration duration(String key, Duration fallback) {
        return repo.findById(key)
                .map(e -> Duration.parse(toIso(unquote(e.getValue()))))
                .orElse(fallback);
    }

    private int intValue(String key, int fallback) {
        return repo.findById(key)
                .map(e -> Integer.parseInt(unquote(e.getValue()).trim()))
                .orElse(fallback);
    }

    private void requireKnown(String key) {
        if (!effective().containsKey(key)) {
            throw new IllegalArgumentException("Unknown setting key: " + key);
        }
    }

    private void validate(String key, String rawValue) {
        requireKnown(key);
        String value = unquote(rawValue);
        switch (key) {
            case TIER_A, TIER_B, TIER_C -> {
                Duration d = Duration.parse(toIso(value));
                if (d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException(key + " must be a positive duration");
                }
            }
            case RATE_LIMIT, RETENTION_DAYS, BULK_CAP, EVENTS_RETENTION_HOURS, EVENTS_BUFFER_SIZE -> {
                int n = Integer.parseInt(value.trim());
                if (n < 1) {
                    throw new IllegalArgumentException(key + " must be at least 1");
                }
            }
            default -> throw new IllegalArgumentException("Unknown setting key: " + key);
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
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
