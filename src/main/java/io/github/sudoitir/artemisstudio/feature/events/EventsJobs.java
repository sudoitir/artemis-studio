package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EventsJobs {

    @Bean
    ScheduledJob eventsFlushJob(BrokerEventWriter writer, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "events-flush", "events", () -> settings.duration(EventsSettings.FLUSH), writer::flush);
    }

    /**
     * The last flush, after jobs have stopped and while the database is still up, so events
     * received before shutdown are written rather than dropped with the process.
     */
    @Bean
    ShutdownStep brokerEventsShutdown(BrokerEventWriter writer) {
        return new ShutdownStep("broker-events", ShutdownPhases.BUFFERS, writer::drain);
    }

    @Bean
    ScheduledJob eventsReaperJob(BrokerEventReaper reaper, SettingsService settings) {
        return ScheduledJob.cron(
                "events-reaper", "events", () -> settings.value(EventsSettings.REAPER_CRON), reaper::reap);
    }
}
