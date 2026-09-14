package io.github.sudoitir.artemisstudio.kernel.jobs;

import java.time.Duration;
import java.time.Instant;

/**
 * What is known about one job's runs since Studio started (operational-health spec).
 *
 * @param registeredAt when the job was scheduled; the reference for a job that has never finished
 * @param lastError the message of the most recent run's failure; {@code null} once a later run succeeds
 * @param runs every started run, successful or not
 * @param nextRun when the scheduler next intends to start it, once it has asked
 * @param interval the gap the scheduler last computed before that next run
 */
public record JobStatus(
        String id,
        String featureId,
        Instant registeredAt,
        Instant lastStart,
        Instant lastEnd,
        String lastError,
        long runs,
        long failures,
        Instant nextRun,
        Duration interval) {

    static JobStatus never(ScheduledJob job, Instant at) {
        return new JobStatus(job.id(), job.featureId(), at, null, null, null, 0, 0, null, null);
    }

    JobStatus started(Instant at) {
        return new JobStatus(
                id, featureId, registeredAt, at, lastEnd, lastError, runs + 1, failures, nextRun, interval);
    }

    JobStatus succeeded(Instant at) {
        return new JobStatus(id, featureId, registeredAt, lastStart, at, null, runs, failures, nextRun, interval);
    }

    JobStatus failed(Instant at, Throwable cause) {
        return new JobStatus(
                id,
                featureId,
                registeredAt,
                lastStart,
                at,
                String.valueOf(cause.getMessage()),
                runs,
                failures + 1,
                nextRun,
                interval);
    }

    JobStatus scheduled(Instant next, Duration gap) {
        return new JobStatus(id, featureId, registeredAt, lastStart, lastEnd, lastError, runs, failures, next, gap);
    }

    /** When a run last finished, or when the job was registered if none has. */
    public Instant lastCompletedOrRegistered() {
        return lastEnd != null ? lastEnd : registeredAt;
    }

    /**
     * Whether no run has finished within three of the job's own intervals. Unknown until the
     * scheduler has computed an interval, and a job with none is never called stalled.
     */
    public boolean degraded(Instant now) {
        return interval != null
                && !interval.isZero()
                && now.isAfter(lastCompletedOrRegistered().plus(interval.multipliedBy(3)));
    }
}
