package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RrJobs {

    @Bean
    ScheduledJob rrDeadlineSweepJob(RrDeadlineSweep sweep, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "rr-deadline-sweep", "rr", () -> settings.duration(RrSettings.SWEEP_INTERVAL), sweep::sweep);
    }

    @Bean
    ScheduledJob rrSamplerJob(RrSampler sampler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "rr-sampler", "rr", () -> settings.duration(RrSettings.SAMPLE_INTERVAL), sampler::tick);
    }

    @Bean
    ScheduledJob rrFlowReaperJob(RrFlowReaper reaper, SettingsService settings) {
        return ScheduledJob.cron("rr-flow-reaper", "rr", () -> settings.value(RrSettings.REAPER_CRON), reaper::reap);
    }
}
