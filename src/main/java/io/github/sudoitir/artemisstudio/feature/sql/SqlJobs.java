package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SqlJobs {

    @Bean
    ShutdownStep captureShutdown(CaptureConsumer consumer) {
        return new ShutdownStep("capture", ShutdownPhases.SUBSCRIPTIONS, consumer::closeAll);
    }

    /** In-flight tail polls finish, and no new one starts, before broker calls are refused. */
    @Bean
    ShutdownStep sqlTailShutdown(SqlTailPoller poller) {
        return new ShutdownStep("sql-tail-polls", ShutdownPhases.BROKER_CALLS, poller::closePolls, poller::resumePolls);
    }

    /**
     * The tail cadence is a property rather than a setting: it is the interval at which
     * an operator's own open query re-reads a broker, floored by
     * {@code sql.min-tail-interval} so no configuration can turn it into a hot loop.
     * The tick is free when nobody is tailing.
     */
    @Bean
    ScheduledJob sqlTailJob(SqlTailPoller poller, SqlProperties properties) {
        return ScheduledJob.fixedDelay("sql-tail", "sql", () -> properties.tailInterval(), poller::tick);
    }

    /**
     * Reconciling index subscriptions is a database read, not a broker call: it decides
     * which tails should exist, so a subscription created in Settings starts capturing
     * within one pass rather than at the next restart.
     */
    @Bean
    ScheduledJob messageIndexReconcileJob(MessageIndexCapture capture) {
        return ScheduledJob.fixedDelay(
                "message-index-reconcile", "sql", () -> MessageIndexCapture.RECONCILE, capture::reconcile);
    }

    /**
     * Capture's converge loop (ADR-0062 D4). It does make broker calls — it reads each
     * live node's divert names and installs what is missing — so it takes a per-node
     * permit, acts only on drift, and is the failover path as well as the install path.
     */
    @Bean
    ScheduledJob captureReconcileJob(CaptureReconciler reconciler, CaptureProperties properties) {
        return ScheduledJob.fixedDelay(
                "capture-reconcile", "sql", () -> properties.reconcileInterval(), reconciler::reconcile);
    }

    /**
     * One partition-maintenance hour for both partitioned tables — the setting is
     * "when Studio may take brief exclusive locks on its own tables", and there is no
     * reason for the index to want a different answer than the metrics.
     */
    @Bean
    ScheduledJob messageIndexPartitionJob(MessageIndexPartitionMaintainer maintainer, SettingsService settings) {
        return ScheduledJob.cron(
                "message-index-partitions",
                "sql",
                () -> settings.value(ScrapeSettings.METRIC_PARTITION_CRON),
                maintainer::maintain);
    }
}
