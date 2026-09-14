package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.Trigger;
import org.springframework.stereotype.Component;

/**
 * The status of every registered job, and the instrumentation that keeps it current:
 * start, end, last error, run and failure counts, the next scheduled run, and a
 * {@code studio.job} timer tagged with the job and its module.
 *
 * <p>A scheduler registers a job with both halves: {@code addTriggerTask(instrument(job),
 * trigger(job))}.
 */
@Component
public class JobStatuses {

    private final Map<String, JobStatus> byId = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    /** Guards {@link #paused} and {@link #inFlight}; runs and {@link #pause} wait on it. */
    private final Object gate = new Object();

    private boolean paused;
    private int inFlight;

    public JobStatuses(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Wraps a job's task so every run is recorded. A failure is recorded and rethrown,
     * so the scheduler's own error handling — log and keep the schedule — is unchanged.
     */
    public Runnable instrument(ScheduledJob job) {
        if (byId.putIfAbsent(job.id(), JobStatus.never(job, Instant.now())) != null) {
            throw new IllegalStateException("Job id '" + job.id() + "' is registered twice");
        }
        Timer timer = Timer.builder("studio.job")
                .description("Background job run duration")
                .tag("job", job.id())
                .tag("feature", job.featureId())
                .register(meters);
        return () -> {
            synchronized (gate) {
                if (paused) {
                    return;
                }
                inFlight++;
            }
            byId.computeIfPresent(job.id(), (k, s) -> s.started(Instant.now()));
            long started = System.nanoTime();
            try {
                job.task().run();
                byId.computeIfPresent(job.id(), (k, s) -> s.succeeded(Instant.now()));
            } catch (RuntimeException | Error e) {
                byId.computeIfPresent(job.id(), (k, s) -> s.failed(Instant.now(), e));
                throw e;
            } finally {
                timer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                synchronized (gate) {
                    inFlight--;
                    gate.notifyAll();
                }
            }
        };
    }

    /**
     * Stop every job from starting a new run, and wait up to {@code maxWait} for runs already
     * in progress (operational-health spec: no job starts a broker call once shutdown has begun).
     * A run still going after the wait is left to finish; the later phases close what it uses.
     */
    public void pause(Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        synchronized (gate) {
            paused = true;
            try {
                long remaining;
                while (inFlight > 0 && (remaining = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                    gate.wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Let jobs run again, after a stopped context is started. */
    public void resume() {
        synchronized (gate) {
            paused = false;
        }
    }

    /**
     * The job's trigger, recording each next run it computes and the interval that led to
     * it, so status can say when the job is next due and health can tell a stalled job.
     */
    public Trigger trigger(ScheduledJob job) {
        return context -> {
            Instant next = job.trigger().nextExecution(context);
            if (next != null) {
                Instant from = context.lastCompletion() != null ? context.lastCompletion() : Instant.now();
                Duration gap = Duration.between(from, next);
                byId.computeIfPresent(job.id(), (k, s) -> s.scheduled(next, gap.isNegative() ? Duration.ZERO : gap));
            }
            return next;
        };
    }

    /** Every registered job, ordered by id. */
    public List<JobStatus> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(JobStatus::id))
                .toList();
    }
}
