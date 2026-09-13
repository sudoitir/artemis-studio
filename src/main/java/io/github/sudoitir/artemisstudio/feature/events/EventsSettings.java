package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Broker-event history retention and write buffering (ADR-0028). */
@Component
@RequiredArgsConstructor
public class EventsSettings implements SettingsContribution {

    public static final String RETENTION_HOURS = "events.retention-hours";
    public static final String REAPER_CRON = "events.reaper-cron";
    public static final String BUFFER_SIZE = "events.buffer-size";
    public static final String FLUSH = "events.flush";

    private final EventsProperties defaults;
    private final BrokerEventReaper reaper;
    private final BrokerEventWriter writer;

    @Override
    public String featureId() {
        return "events";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        RETENTION_HOURS,
                        "Retention",
                        "Event retention (hours)",
                        "broker_event rows older than this are trimmed.",
                        Kind.INT,
                        () -> Long.toString(defaults.retention().toHours()),
                        s -> reaper.setRetentionHours(s.intValue(RETENTION_HOURS))),
                new SettingDef(
                        REAPER_CRON,
                        "Retention",
                        "Event reaper schedule",
                        "When the broker_event trim runs. Six-field cron.",
                        Kind.CRON,
                        () -> defaults.reaperCron(),
                        null),
                new SettingDef(
                        BUFFER_SIZE,
                        "Broker events",
                        "Write buffer size",
                        "Bounded queue of unwritten events. Overflow is dropped and counted, never blocked on.",
                        Kind.INT,
                        () -> Integer.toString(defaults.bufferSize()),
                        s -> writer.setCapacity(s.intValue(BUFFER_SIZE))),
                new SettingDef(
                        FLUSH,
                        "Broker events",
                        "Flush interval",
                        "How often the buffered events are batch-inserted.",
                        Kind.DURATION,
                        () -> defaults.flush().toString(),
                        null));
    }
}
