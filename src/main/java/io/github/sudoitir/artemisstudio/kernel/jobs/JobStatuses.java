package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.springframework.scheduling.Trigger;
import org.springframework.stereotype.Component;

/**
 * The status of every registered job, and the instrumentation that keeps it current:
 * start, end, last error, run and failure counts, the next scheduled run, and a
 * {@code studio.job} observation (a timer, and a span when tracing is exported) tagged with the job and its module.
 *
 * <p>A scheduler registers a job with both halves: {@code addTriggerTask(instrument(job),
 * trigger(job))}. An {@link ScheduledJob.Scope#INSTALLATION installation-wide} job runs only while
 * it holds its ShedLock lock (ADR-0125); a tick that finds it held elsewhere is recorded as skipped.
 */
@Component
@PluginApi
public class JobStatuses {

    private static final String JOB_ID = "job.id";

    /** A floor under every lock's lifetime; ShedLock's keep-alive extends it while a run lasts. */
    private static final Duration LOCK_AT_MOST = Duration.ofSeconds(60);

    private final Map<String, JobStatus> byId = new ConcurrentHashMap<>();
    private final LockingTaskExecutor locks;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    /** Guards {@link #paused} and {@link #inFlight}; runs and {@link #pause} wait on it. */
    private final Object gate = new Object();

    private boolean paused;
    private int inFlight;

    public JobStatuses(LockingTaskExecutor locks, ObservationRegistry observations, MeterRegistry meters) {
        this.locks = locks;
        this.observations = observations;
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
        Gauge.builder("studio.job.lag", () -> lagSeconds(job.id()))
                .tag(JOB_ID, job.id())
                .baseUnit("seconds")
                .description(
                        "Seconds a job is past its interval since it last completed; NaN until its interval is known")
                .register(meters);
        Gauge.builder("studio.job.degraded", () -> degraded(job.id()))
                .tag(JOB_ID, job.id())
                .description("1 while no run has finished within three of the job's intervals, else 0")
                .register(meters);
        return () -> {
            synchronized (gate) {
                if (paused) {
                    return;
                }
                inFlight++;
            }
            try {
                if (job.scope() == ScheduledJob.Scope.INSTALLATION) {
                    runOnce(job);
                } else {
                    runRecorded(job);
                }
            } finally {
                synchronized (gate) {
                    inFlight--;
                    gate.notifyAll();
                }
            }
        };
    }

    private double lagSeconds(String jobId) {
        JobStatus status = byId.get(jobId);
        Duration lag = status == null ? null : status.lag(Instant.now());
        return lag == null ? Double.NaN : lag.toMillis() / 1000.0;
    }

    private double degraded(String jobId) {
        JobStatus status = byId.get(jobId);
        return status != null && status.degraded(Instant.now()) ? 1 : 0;
    }

    private void runRecorded(ScheduledJob job) {
        byId.computeIfPresent(job.id(), (k, s) -> s.started(Instant.now()));
        try {
            observation(job).observe(job.task());
            byId.computeIfPresent(job.id(), (k, s) -> s.succeeded(Instant.now()));
        } catch (RuntimeException | Error e) {
            byId.computeIfPresent(job.id(), (k, s) -> s.failed(Instant.now(), e));
            throw e;
        }
    }

    /** One run as an observation: the {@code studio.job} timer and, when export is on, a span. */
    private Observation observation(ScheduledJob job) {
        return Observation.createNotStarted("studio.job", observations)
                .contextualName("job " + job.id())
                .lowCardinalityKeyValue(JOB_ID, job.id())
                .lowCardinalityKeyValue("feature", job.featureId());
    }

    /** Runs the job only while holding its lock; otherwise another instance has this tick. */
    private void runOnce(ScheduledJob job) {
        Duration gap = job.minimumGap().get();
        Duration atMost = gap.compareTo(LOCK_AT_MOST) > 0 ? gap : LOCK_AT_MOST;
        TaskResult<Void> result;
        try {
            result = locks.executeWithLock(
                    () -> {
                        runRecorded(job);
                        return null;
                    },
                    new LockConfiguration(Instant.now(), job.id(), atMost, gap));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        if (!result.wasExecuted()) {
            byId.computeIfPresent(job.id(), (k, s) -> s.skippedElsewhere(Instant.now()));
        }
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
            } catch (InterruptedException _) {
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

    /**
     * Removes a job's status row (design.md, task 6.4) — called on plugin deactivation, after its
     * {@link org.springframework.scheduling.Trigger} has already been cancelled, so nothing can
     * re-add it. A job id that was never registered is a no-op.
     */
    public void deregister(String jobId) {
        byId.remove(jobId);
        meters.find("studio.job.lag").tag(JOB_ID, jobId).meters().forEach(meters::remove);
        meters.find("studio.job.degraded").tag(JOB_ID, jobId).meters().forEach(meters::remove);
    }

    /** Every registered job, ordered by id. */
    public List<JobStatus> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(JobStatus::id))
                .toList();
    }
}
