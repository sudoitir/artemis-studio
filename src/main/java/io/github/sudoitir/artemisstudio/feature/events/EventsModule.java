package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Broker notification history. Module descriptor (ADR-0070). */
public final class EventsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("events")
            .title("Broker events")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/events")
            .settingKey(EventsSettings.RETENTION_HOURS)
            .settingKey(EventsSettings.REAPER_CRON)
            .settingKey(EventsSettings.BUFFER_SIZE)
            .settingKey(EventsSettings.FLUSH)
            .build();

    private EventsModule() {}
}
