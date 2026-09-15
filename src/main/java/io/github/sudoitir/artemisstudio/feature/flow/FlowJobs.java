package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FlowJobs {

    /** Samples observed clusters' producers and consumers; does nothing while none is observed (ADR-0081). */
    @Bean
    ScheduledJob flowSampleJob(ClientSampler sampler, SettingsService settings) {
        return ScheduledJob.fixedDelay(
                "flow-sample", "flow", () -> FlowSettings.sampleInterval(settings), sampler::sweepObserved);
    }
}
