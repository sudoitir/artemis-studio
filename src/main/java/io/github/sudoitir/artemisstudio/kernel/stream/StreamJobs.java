package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StreamJobs {

    static final Duration SESSION_CHECK_INTERVAL = Duration.ofSeconds(10);

    @Bean
    ScheduledJob sseHeartbeatJob(SseHub hub, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "sse-heartbeat",
                "stream",
                ScheduledJob.Scope.INSTANCE,
                () -> settings.duration(StreamSettings.HEARTBEAT_INTERVAL),
                hub::heartbeat);
    }

    /**
     * Ends the streams of sessions that timed out or ended elsewhere, at a fixed gap: how soon a
     * signed-out user stops receiving events must not depend on the proxy-tuned heartbeat.
     */
    @Bean
    ScheduledJob sseSessionCheckJob(SseHub hub, SessionAuthentication sessions) {
        return ScheduledJob.fixedDelay(
                "sse-session-check",
                "stream",
                ScheduledJob.Scope.INSTANCE,
                () -> SESSION_CHECK_INTERVAL,
                () -> hub.closeEndedSessions(sessions::isLive));
    }
}
