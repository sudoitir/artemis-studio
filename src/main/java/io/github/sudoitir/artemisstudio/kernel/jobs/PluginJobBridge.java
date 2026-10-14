package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Schedules a plugin's own {@link ScheduledJob} beans on the {@link JobRunner} on activation, and
 * cancels and deregisters them on deactivation (design.md, task 6.4). A plugin's runs are virtual threads
 * of their own, like every job's, so a plugin job can never delay, or be delayed by, a built-in one.
 */
@Component
class PluginJobBridge implements PluginBridge {

    private final JobRunner runner;

    private final Map<String, Registered> byPlugin = new ConcurrentHashMap<>();

    /**
     * {@code handle} is the owner this registration belongs to. {@link #attach} itself supersedes
     * whatever the previous owner registered (see its own javadoc), so by the time {@link #detach}
     * runs for a superseded version it is always a no-op — it must still guard on {@code handle}
     * identity rather than unconditionally clearing {@code byPlugin}, in case it somehow runs
     * before the newer version's {@link #attach} for the same id.
     */
    private record Registered(PluginHandle handle, List<String> jobIds) {}

    PluginJobBridge(JobRunner runner) {
        this.runner = runner;
    }

    @Override
    public void attach(PluginHandle handle) {
        // The Instant activation class attaches this (v2) before the old version (v1) detaches,
        // and a plugin's job id is stable across its own versions by convention (it is how the
        // UI keeps identifying "the same job" across an update). Superseding the previous
        // registration for this plugin id here — rather than waiting for v1's own detach — is
        // what makes that coexistence possible: the runner rejects a second registration under
        // the same job id outright, so if this didn't happen first, every job-declaring plugin's
        // Instant update would fail. v1's later, owner-guarded detach() then finds itself already
        // superseded and is a no-op, exactly like every other bridge's owners map.
        Registered previous = byPlugin.get(handle.id());
        if (previous != null) {
            previous.jobIds().forEach(runner::cancel);
        }

        List<ScheduledJob> jobs = List.copyOf(
                handle.applicationContext().getBeansOfType(ScheduledJob.class).values());
        for (ScheduledJob job : jobs) {
            runner.schedule(onThePlugin(handle, job));
        }
        byPlugin.put(
                handle.id(),
                new Registered(handle, jobs.stream().map(ScheduledJob::id).toList()));
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
        registered.jobIds().forEach(runner::cancel);
    }

    /**
     * The job with its task run through {@link PluginHandle#runInPlugin(java.util.concurrent.Callable)}
     * (design.md §2, R2-M5): the thread context classloader is switched to the plugin's own for the
     * run, and the run counts as in-flight so {@code PluginRuntime#close()}'s drain waits for it. The
     * run's thread is a virtual thread that ends with the run, so nothing keeps the plugin's
     * classloader reachable afterwards.
     */
    private static ScheduledJob onThePlugin(PluginHandle handle, ScheduledJob job) {
        Runnable task = () -> {
            try {
                handle.runInPlugin(() -> {
                    job.task().run();
                    return null;
                });
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        return new ScheduledJob(
                job.id(), job.featureId(), job.scope(), job.trigger(), job.minimumGap(), job.timeout(), task);
    }
}
