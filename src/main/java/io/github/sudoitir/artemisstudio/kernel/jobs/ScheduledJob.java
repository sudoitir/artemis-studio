package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.scheduling.Trigger;

/**
 * One background task a module runs on a schedule (ADR-0048). Declared as a bean in
 * the owning module; the jobs kernel registers it, records its status and times it.
 * A disabled module's jobs are never declared, so they never run.
 *
 * <p>Every job states its {@link Scope} (ADR-0125). A job whose effect is only in the shared
 * database or on the brokers is {@link Scope#INSTALLATION INSTALLATION}: when several instances
 * share one database it runs on one of them per tick. A job that drains, refills or reconciles
 * this instance's own memory, streams or consumers is {@link Scope#INSTANCE INSTANCE}, and runs
 * everywhere.
 *
 * @param id stable, unique across the installation — it names the job in status and metrics, and
 *     is the lock name of an installation-wide job
 * @param featureId the owning module
 * @param minimumGap how long an installation-wide run keeps its lock after it ends, so another
 *     instance cannot repeat the same tick; re-read on every run
 */
@PluginApi
public record ScheduledJob(
        String id, String featureId, Scope scope, Trigger trigger, Supplier<Duration> minimumGap, Runnable task) {

    /** Where a job runs when several instances share one database. */
    public enum Scope {
        /** On every instance: the job works on this instance's own state. */
        INSTANCE,
        /** On one instance per tick: the job works on shared state. */
        INSTALLATION
    }

    /** The longest an installation-wide fixed-delay run holds its lock after it ends. */
    private static final Duration MAX_GAP = Duration.ofMinutes(5);

    /** How long an installation-wide cron run holds its lock: long enough to cover clock skew. */
    private static final Duration CRON_GAP = Duration.ofSeconds(30);

    /**
     * Fixed delay from the end of the previous run; the interval is re-read every fire. An
     * installation-wide run keeps its lock for 90% of the interval (at most five minutes) after it
     * ends, so the other instances' next ticks find it held.
     */
    public static ScheduledJob fixedDelay(
            String id, String featureId, Scope scope, Supplier<Duration> interval, Runnable task) {
        Supplier<Duration> gap = () -> {
            Duration ninety = interval.get().multipliedBy(9).dividedBy(10);
            return ninety.compareTo(MAX_GAP) > 0 ? MAX_GAP : ninety;
        };
        return new ScheduledJob(id, featureId, scope, DynamicTriggers.fixedDelay(interval), gap, task);
    }

    /** A cron whose expression is re-read every fire. */
    public static ScheduledJob cron(
            String id, String featureId, Scope scope, Supplier<String> expression, Runnable task) {
        return new ScheduledJob(id, featureId, scope, DynamicTriggers.cron(expression), () -> CRON_GAP, task);
    }
}
