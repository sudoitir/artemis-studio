package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.broker.capture.CaptureReconciler;
import io.github.sudoitir.artemisstudio.broker.core.RrSampler;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.BrokerEventReaper;
import io.github.sudoitir.artemisstudio.persist.BrokerEventWriter;
import io.github.sudoitir.artemisstudio.persist.MessageIndexPartitionMaintainer;
import io.github.sudoitir.artemisstudio.persist.MetricPartitionMaintainer;
import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.RrFlowReaper;
import io.github.sudoitir.artemisstudio.service.ClockOffsetService;
import io.github.sudoitir.artemisstudio.service.SettingsService;
import io.github.sudoitir.artemisstudio.sql.MessageIndexCapture;
import io.github.sudoitir.artemisstudio.sql.SqlTailPoller;
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
    private final ClockOffsetService clockOffsets;
    private final MonotonicClockWatch monotonicClockWatch;
    private final SqlTailPoller sqlTailPoller;
    private final MessageIndexCapture messageIndexCapture;
    private final CaptureReconciler captureReconciler;
    private final MessageIndexPartitionMaintainer messageIndexPartitions;
    private final ArtemisStudioProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Sub-minute cadences.
        registrar.addTriggerTask(eventWriter::flush, DynamicTriggers.fixedDelay(settings::eventsFlush));
        registrar.addTriggerTask(rrDeadlineSweep::sweep, DynamicTriggers.fixedDelay(settings::rrSweepInterval));
        registrar.addTriggerTask(rrSampler::tick, DynamicTriggers.fixedDelay(settings::rrSampleInterval));
        registrar.addTriggerTask(
                alertDispatcher::dispatch, DynamicTriggers.fixedDelay(settings::alertingDispatchInterval));
        registrar.addTriggerTask(sseHub::heartbeat, DynamicTriggers.fixedDelay(settings::sseHeartbeatInterval));
        // Not settings-tunable: both are properties of how time is measured rather
        // than of how hard Studio polls a broker, and neither makes a broker call.
        registrar.addTriggerTask(
                clockOffsets::refresh, DynamicTriggers.fixedDelay(() -> ClockOffsetService.REFRESH_INTERVAL));
        registrar.addTriggerTask(
                monotonicClockWatch::check, DynamicTriggers.fixedDelay(() -> MonotonicClockWatch.INTERVAL));
        // The tail cadence is a property rather than a setting: it is the interval at
        // which an operator's own open query re-reads a broker, floored by
        // `sql.min-tail-interval` so no configuration can turn it into a hot loop. The
        // tick is free when nobody is tailing.
        registrar.addTriggerTask(
                sqlTailPoller::tick,
                DynamicTriggers.fixedDelay(() -> properties.sql().tailInterval()));
        // Reconciling index subscriptions is a database read, not a broker call: it
        // decides which tails should exist, and the tick above is what reads. A
        // subscription created in Settings therefore starts capturing within one pass
        // rather than at the next restart.
        registrar.addTriggerTask(
                messageIndexCapture::reconcile, DynamicTriggers.fixedDelay(() -> MessageIndexCapture.RECONCILE));
        // Capture's converge loop. Unlike the sampled reconcile above this one does
        // make broker calls — it reads each live node's divert names and installs what
        // is missing — so it is a per-node permit through NodeCallLimiter, acts only on
        // drift, and is the failover path as well as the install path (ADR-0062 D4).
        registrar.addTriggerTask(
                captureReconciler::reconcile,
                DynamicTriggers.fixedDelay(() -> properties.capture().reconcileInterval()));

        // Housekeeping crons.
        registrar.addTriggerTask(metricReaper::reap, DynamicTriggers.cron(settings::metricReaperCron));
        registrar.addTriggerTask(eventReaper::reap, DynamicTriggers.cron(settings::eventsReaperCron));
        registrar.addTriggerTask(rrFlowReaper::reap, DynamicTriggers.cron(settings::rrReaperCron));
        registrar.addTriggerTask(partitionMaintainer::maintain, DynamicTriggers.cron(settings::metricPartitionCron));
        // One partition-maintenance hour for both partitioned tables — the setting is
        // "when Studio may take brief exclusive locks on its own tables", and there is
        // no reason for the index to want a different answer than the metrics.
        registrar.addTriggerTask(messageIndexPartitions::maintain, DynamicTriggers.cron(settings::metricPartitionCron));
    }
}
