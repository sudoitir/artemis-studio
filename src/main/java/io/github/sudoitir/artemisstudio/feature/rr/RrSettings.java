package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.core.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Request-reply tracing deadlines, capture, cadences and retention. */
@Component
@RequiredArgsConstructor
public class RrSettings implements SettingsContribution {

    public static final String DEFAULT_DEADLINE_MS = "rr.default-deadline-ms";
    public static final String PAYLOAD_CAPTURE_BYTES = "rr.payload-capture-bytes";
    public static final String SWEEP_INTERVAL = "rr.sweep-interval";
    public static final String SAMPLE_INTERVAL = "rr.sample-interval";
    public static final String RETENTION_DAYS = "rr.retention-days";
    public static final String REAPER_CRON = "rr.reaper-cron";

    private final ArtemisStudioProperties defaults;
    private final RrCorrelator correlator;
    private final RrFlowReaper reaper;

    @Override
    public String featureId() {
        return "rr";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        RETENTION_DAYS,
                        "Retention",
                        "Request-reply retention (days)",
                        "rr_flow and rr_event rows older than this are trimmed.",
                        Kind.INT,
                        () -> Long.toString(defaults.rr().retention().toDays()),
                        s -> reaper.setRetentionDays(s.intValue(RETENTION_DAYS))),
                new SettingDef(
                        REAPER_CRON,
                        "Retention",
                        "Request-reply reaper schedule",
                        "When the rr_flow trim runs. Six-field cron.",
                        Kind.CRON,
                        () -> defaults.rr().reaperCron(),
                        null),
                new SettingDef(
                        DEFAULT_DEADLINE_MS,
                        "Request-reply",
                        "Default deadline (ms)",
                        "Used only when neither the message nor its expectation carries a deadline.",
                        Kind.INT,
                        () -> Integer.toString(defaults.rr().defaultDeadlineMs()),
                        s -> correlator.setDefaultDeadlineMs(s.intValue(DEFAULT_DEADLINE_MS))),
                new SettingDef(
                        PAYLOAD_CAPTURE_BYTES,
                        "Request-reply",
                        "Payload capture cap (bytes)",
                        "How much of a request or reply body is stored when an expectation enables capture.",
                        Kind.INT,
                        () -> Integer.toString(defaults.rr().payloadCaptureBytes()),
                        s -> correlator.setPayloadCaptureBytes(s.intValue(PAYLOAD_CAPTURE_BYTES))),
                new SettingDef(
                        SWEEP_INTERVAL,
                        "Request-reply",
                        "Deadline sweep interval",
                        "How often flows past their deadline are marked timed out or orphaned.",
                        Kind.DURATION,
                        () -> defaults.rr().sweepInterval().toString(),
                        null),
                new SettingDef(
                        SAMPLE_INTERVAL,
                        "Request-reply",
                        "Sampler interval",
                        "How often enabled expectations are sampled over the Core transport. "
                                + "It is also the error bar on any latency measured by observation.",
                        Kind.DURATION,
                        () -> defaults.rr().sampleInterval().toString(),
                        // The interval is the width of the error bar Studio reports next to an
                        // observed latency, so the two must never disagree.
                        s -> correlator.setSampleIntervalMs(
                                (int) s.duration(SAMPLE_INTERVAL).toMillis())));
    }
}
