package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.partition.PartitionSpec;
import java.time.Duration;
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
        return ScheduledJob.fixedDelay(
                "sql-tail", "sql", ScheduledJob.Scope.INSTANCE, properties::tailInterval, poller::tick);
    }

    /**
     * Reconciling index subscriptions is a database read, not a broker call: it decides
     * which tails should exist, so a subscription created in Settings starts capturing
     * within one pass rather than at the next restart.
     */
    @Bean
    ScheduledJob messageIndexReconcileJob(MessageIndexCapture capture) {
        return ScheduledJob.fixedDelay(
                "message-index-reconcile",
                "sql",
                ScheduledJob.Scope.INSTANCE,
                () -> MessageIndexCapture.RECONCILE,
                capture::reconcileSampling);
    }

    /**
     * Capture's converge loop (ADR-0062 D4). It does make broker calls — it reads each
     * live node's divert names and installs what is missing — so it takes a per-node
     * permit, acts only on drift, and is the failover path as well as the install path.
     */
    @Bean
    ScheduledJob captureReconcileJob(CaptureReconciler reconciler, CaptureProperties properties) {
        return ScheduledJob.fixedDelay(
                "capture-reconcile",
                "sql",
                ScheduledJob.Scope.INSTANCE,
                properties::reconcileInterval,
                reconciler::reconcile);
    }

    /**
     * A drain holds what it has read until it can acknowledge a full batch, which on a low-rate
     * address never comes. This commits the partial batches, so a captured message is answerable —
     * and off the capture queue — within one interval rather than after another forty-nine arrive.
     * It touches no broker: the store is Postgres and the acknowledge is on a session already open.
     */
    @Bean
    ScheduledJob captureFlushJob(CaptureConsumer consumer, CaptureProperties properties) {
        return ScheduledJob.fixedDelay(
                "capture-flush", "sql", ScheduledJob.Scope.INSTANCE, properties::flushInterval, consumer::flushAll);
    }

    @Bean
    PartitionSpec messageIndexPartitions() {
        return MessageIndexStore.PARTITIONS;
    }

    /** Keeps each subscription's own, shorter retention on the shared index (ADR-0059). */
    @Bean
    ScheduledJob messageIndexRetentionJob(MessageIndexRetention retention) {
        return ScheduledJob.fixedDelay(
                "message-index-retention",
                "sql",
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofHours(1),
                retention::expire);
    }
}
