package io.github.sudoitir.artemisstudio.feature.events;

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

    @Bean
    ScheduledJob eventsReaperJob(BrokerEventReaper reaper, SettingsService settings) {
        return ScheduledJob.cron(
                "events-reaper", "events", () -> settings.value(EventsSettings.REAPER_CRON), reaper::reap);
    }
}
