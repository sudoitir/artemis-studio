package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StreamJobs {

    @Bean
    ScheduledJob sseHeartbeatJob(SseHub hub, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "sse-heartbeat", "stream", () -> settings.duration(StreamSettings.HEARTBEAT_INTERVAL), hub::heartbeat);
    }
}
