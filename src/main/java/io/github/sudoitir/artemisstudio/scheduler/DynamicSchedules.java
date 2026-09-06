package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.broker.core.RrSampler;
import io.github.sudoitir.artemisstudio.persist.BrokerEventReaper;
import io.github.sudoitir.artemisstudio.persist.BrokerEventWriter;
import io.github.sudoitir.artemisstudio.persist.MetricPartitionMaintainer;
import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.RrFlowReaper;
import io.github.sudoitir.artemisstudio.service.SettingsService;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Every settings-driven schedule outside the scrape tiers (ADR-0048). Each task
 * is registered here as a trigger that re-reads {@link SettingsService} when
 * computing its next run, so changing a cadence or a cron in Settings applies on
 * the following fire — no restart, and no second place where the interval is
 * written down.
 *
 * <p>The tasks themselves keep their own class: this owns <em>when</em> they run,
 * they own <em>what</em> they do. That split is why the beans below have no
 * {@code @Scheduled} annotation left — an annotation and a trigger for the same
 * method would run it twice, so the annotation is removed rather than disabled.
 *
 * <p>The scrape tiers stay in {@link ScrapeScheduler} because they need their own
 * task scheduler: a slow tier must not delay these, and a slow reaper must not
 * delay a tier.
 */
@Component
@DependsOn("settingsService")
@RequiredArgsConstructor
public class DynamicSchedules implements SchedulingConfigurer {

    private final SettingsService settings;
    private final BrokerEventWriter eventWriter;
    private final BrokerEventReaper eventReaper;
    private final MetricSampleReaper metricReaper;
    private final MetricPartitionMaintainer partitionMaintainer;
    private final RrFlowReaper rrFlowReaper;
    private final RrDeadlineSweep rrDeadlineSweep;
    private final RrSampler rrSampler;
    private final AlertDispatcher alertDispatcher;
    private final SseHub sseHub;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Sub-minute cadences.
        registrar.addTriggerTask(eventWriter::flush, DynamicTriggers.fixedDelay(settings::eventsFlush));
        registrar.addTriggerTask(rrDeadlineSweep::sweep, DynamicTriggers.fixedDelay(settings::rrSweepInterval));
        registrar.addTriggerTask(rrSampler::tick, DynamicTriggers.fixedDelay(settings::rrSampleInterval));
        registrar.addTriggerTask(
                alertDispatcher::dispatch, DynamicTriggers.fixedDelay(settings::alertingDispatchInterval));
        registrar.addTriggerTask(sseHub::heartbeat, DynamicTriggers.fixedDelay(settings::sseHeartbeatInterval));

        // Housekeeping crons.
        registrar.addTriggerTask(metricReaper::reap, DynamicTriggers.cron(settings::metricReaperCron));
        registrar.addTriggerTask(eventReaper::reap, DynamicTriggers.cron(settings::eventsReaperCron));
        registrar.addTriggerTask(rrFlowReaper::reap, DynamicTriggers.cron(settings::rrReaperCron));
        registrar.addTriggerTask(partitionMaintainer::maintain, DynamicTriggers.cron(settings::metricPartitionCron));
    }
}
