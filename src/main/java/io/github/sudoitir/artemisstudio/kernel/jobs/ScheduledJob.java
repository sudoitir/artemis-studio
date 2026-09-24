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
 * @param id stable, unique across the installation — it names the job in status and metrics
 * @param featureId the owning module
 */
@PluginApi
public record ScheduledJob(String id, String featureId, Trigger trigger, Runnable task) {

    /** Fixed delay from the end of the previous run; the interval is re-read every fire. */
    public static ScheduledJob fixedDelay(String id, String featureId, Supplier<Duration> interval, Runnable task) {
        return new ScheduledJob(id, featureId, DynamicTriggers.fixedDelay(interval), task);
    }

    /** A cron whose expression is re-read every fire. */
    public static ScheduledJob cron(String id, String featureId, Supplier<String> expression, Runnable task) {
        return new ScheduledJob(id, featureId, DynamicTriggers.cron(expression), task);
    }
}
