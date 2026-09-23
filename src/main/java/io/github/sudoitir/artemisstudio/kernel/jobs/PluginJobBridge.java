package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Schedules a plugin's own {@link ScheduledJob} beans on activation, and cancels and deregisters
 * them on deactivation (design.md, task 6.4). Plugins get their own {@link ThreadPoolTaskScheduler}
 * rather than sharing {@code JobScheduler}'s registrar, so a plugin job can never delay — or be
 * delayed by — a built-in one, the same isolation the scrape tiers already have from the main
 * scheduler.
 */
@Slf4j
@Component
class PluginJobBridge implements PluginBridge {

    private final JobStatuses statuses;
    private final ThreadPoolTaskScheduler scheduler;

    private final Map<String, Registered> byPlugin = new ConcurrentHashMap<>();

    /**
     * {@code handle} is the owner this registration belongs to. {@link #attach} itself supersedes
     * whatever the previous owner registered (see its own javadoc), so by the time {@link #detach}
     * runs for a superseded version it is always a no-op — it must still guard on {@code handle}
     * identity rather than unconditionally clearing {@code byPlugin}, in case it somehow runs
     * before the newer version's {@link #attach} for the same id.
     */
    private record Registered(PluginHandle handle, List<ScheduledFuture<?>> futures, List<String> jobIds) {}

    PluginJobBridge(JobStatuses statuses) {
        this.statuses = statuses;
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("plugin-job-");
        scheduler.initialize();
        this.scheduler = scheduler;
    }

    @Override
    public void attach(PluginHandle handle) {
        // The Instant activation class attaches this (v2) before the old version (v1) detaches,
        // and a plugin's job id is stable across its own versions by convention (it is how the
        // UI keeps identifying "the same job" across an update). Superseding the previous
        // registration for this plugin id here — rather than waiting for v1's own detach — is
        // what makes that coexistence possible: JobStatuses rejects a second registration under
        // the same job id outright, so if this didn't happen first, every job-declaring plugin's
        // Instant update would fail. v1's later, owner-guarded detach() then finds itself already
        // superseded and is a no-op, exactly like every other bridge's owners map.
        Registered previous = byPlugin.get(handle.id());
        if (previous != null) {
            previous.futures().forEach(f -> f.cancel(false));
            previous.jobIds().forEach(statuses::deregister);
        }

        List<ScheduledJob> jobs = List.copyOf(
                handle.applicationContext().getBeansOfType(ScheduledJob.class).values());
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ScheduledJob job : jobs) {
            futures.add(scheduler.schedule(statuses.instrument(job), statuses.trigger(job)));
            ids.add(job.id());
        }
        byPlugin.put(handle.id(), new Registered(handle, futures, ids));
    }

    /**
     * A no-op when {@code handle} is not the current owner of its plugin id: a newer version's
     * {@link #attach} already superseded it, and that newer version's own scheduled jobs must not
     * be cancelled by the old version's detach.
     */
    @Override
    public void detach(PluginHandle handle) {
        Registered registered = byPlugin.get(handle.id());
        if (registered == null || registered.handle() != handle || !byPlugin.remove(handle.id(), registered)) {
            return;
        }
        registered.futures().forEach(f -> f.cancel(false));
        registered.jobIds().forEach(statuses::deregister);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
