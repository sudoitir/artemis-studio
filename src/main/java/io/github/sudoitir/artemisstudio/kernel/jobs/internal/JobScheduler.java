package io.github.sudoitir.artemisstudio.kernel.jobs.internal;

import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Registers every module's {@link ScheduledJob} as a trigger task (ADR-0048). The
 * modules own <em>what</em> runs and <em>when</em>; this owns that it runs, and that
 * each run is recorded. The scrape tiers keep their own scheduler, so a slow tier
 * never delays a job here and a slow job never delays a tier.
 */
@Component
@DependsOn("settingsService")
@RequiredArgsConstructor
class JobScheduler implements SchedulingConfigurer {

    private final List<ScheduledJob> jobs;
    private final JobStatuses statuses;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (ScheduledJob job : jobs) {
            registrar.addTriggerTask(statuses.instrument(job), job.trigger());
        }
    }
}
