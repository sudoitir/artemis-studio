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
        // A new worker thread inherits the calling thread's TCCL at the moment it is spawned
        // (java.lang.Thread's own constructor), and PluginRuntimeFactory holds the calling thread's
        // TCCL switched to the plugin's own classloader for the whole activation — including the
        // moment a plugin's first ScheduledJob lazily grows this pool. Left at Spring's default
        // factory, that pins whichever plugin happened to be activating at the time forever on this
        // shared, long-lived pool, since nothing ever touches a worker thread's TCCL again between
        // runs (design.md's spike results' "classloader leaks are possible" GC root). Explicitly
        // starting every worker thread on this bridge's own (host) classloader instead means a
        // plugin classloader is only ever reachable from here for the duration of one run() —
        // {@link #runOnThePlugin} switches to it and back out again around each task.
        scheduler.setThreadFactory(
                new org.springframework.scheduling.concurrent.CustomizableThreadFactory("plugin-job-") {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = super.newThread(runnable);
                        thread.setContextClassLoader(PluginJobBridge.class.getClassLoader());
                        return thread;
                    }
                });
        scheduler.initialize();
        // ScheduledThreadPoolExecutor's default removeOnCancelPolicy is false: a cancelled-but-
        // not-yet-due task (exactly what detach()'s future.cancel(false) leaves behind) stays
        // sitting in the executor's internal delay queue, still strongly referencing the plugin's
        // job Runnable — and, through it, the plugin's classloader — until its own next fire time
        // would have arrived (minutes away, for most jobs). Enabling the policy here makes a
        // cancel purge the task from that queue immediately, which is what actually lets an
        // unloaded plugin's classloader become collectible promptly rather than minutes late.
        scheduler.getScheduledThreadPoolExecutor().setRemoveOnCancelPolicy(true);
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
            futures.add(scheduler.schedule(runOnThePlugin(handle, statuses.instrument(job)), statuses.trigger(job)));
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

    /**
     * Runs {@code instrumented} through {@link PluginHandle#runInPlugin(java.util.concurrent.Callable)}
     * (design.md §2, R2-M5): the thread context classloader is switched to the plugin's own for the
     * run, and the run counts as in-flight so {@code PluginRuntime#close()}'s drain waits for it.
     *
     * <p>{@code runInPlugin} restores whatever TCCL was set on the calling thread before it — the
     * right thing for a request thread, whose TCCL is neutral again the moment the request ends, but
     * wrong for {@link #scheduler}'s worker threads: they are long-lived and shared across every
     * plugin's whole lifetime, so the very first job that ever runs on a freshly spawned worker
     * thread "restores" whatever TCCL that thread happened to inherit at creation — an accidental
     * leftover from {@code PluginRuntimeFactory}, which switches the calling (activation) thread's
     * TCCL for the whole build, including the moment {@link #attach} lazily spins the pool up. Left
     * alone, that pins the first plugin's classloader on that worker thread forever, since nothing
     * ever sets it to anything else again — exactly the GC root design.md's spike results called
     * out. The {@code finally} below breaks that by resetting the thread back to a classloader nothing
     * ever unloads (the host's own) after every run, rather than trusting "restore previous".
     */
    private Runnable runOnThePlugin(PluginHandle handle, Runnable instrumented) {
        return () -> {
            try {
                handle.runInPlugin(() -> {
                    instrumented.run();
                    return null;
                });
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(PluginJobBridge.class.getClassLoader());
            }
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
