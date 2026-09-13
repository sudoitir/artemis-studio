package io.github.sudoitir.artemisstudio.kernel.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The status of every registered job, and the instrumentation that keeps it current:
 * start, end, last error, run and failure counts, and a {@code studio.job} timer
 * tagged with the job and its module.
 */
@Component
public class JobStatuses {

    private final Map<String, JobStatus> byId = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    public JobStatuses(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Wraps a job's task so every run is recorded. A failure is recorded and rethrown,
     * so the scheduler's own error handling — log and keep the schedule — is unchanged.
     */
    public Runnable instrument(ScheduledJob job) {
        if (byId.putIfAbsent(job.id(), JobStatus.never(job)) != null) {
            throw new IllegalStateException("Job id '" + job.id() + "' is registered twice");
        }
        Timer timer = Timer.builder("studio.job")
                .description("Background job run duration")
                .tag("job", job.id())
                .tag("feature", job.featureId())
                .register(meters);
        return () -> {
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
            }
        };
    }

    /** Every registered job, ordered by id. */
    public List<JobStatus> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(JobStatus::id))
                .toList();
    }
}
