package io.github.sudoitir.artemisstudio.kernel.jobs;

import java.time.Duration;
import java.time.Instant;

/**
 * What is known about one job's runs since Studio started (operational-health spec).
 *
 * @param scope whether it runs on every instance or once per installation (ADR-0125)
 * @param registeredAt when the job was scheduled; the reference for a job that has never finished
 * @param lastError the message of the most recent run's failure; {@code null} once a later run succeeds
 * @param runs every started run, successful or not
 * @param nextRun when the scheduler next intends to start it, once it has asked
 * @param interval the gap the scheduler last computed before that next run
 * @param lastSkippedElsewhere when this instance last found an installation-wide job's lock held by
 *     another instance, which ran it instead
 */
public record JobStatus(
        String id,
        String featureId,
        ScheduledJob.Scope scope,
        Instant registeredAt,
        Instant lastStart,
        Instant lastEnd,
        String lastError,
        long runs,
        long failures,
        Instant nextRun,
        Duration interval,
        Instant lastSkippedElsewhere) {

    static JobStatus never(ScheduledJob job, Instant at) {
        return new JobStatus(job.id(), job.featureId(), job.scope(), at, null, null, null, 0, 0, null, null, null);
    }

    JobStatus started(Instant at) {
        return new JobStatus(
                id,
                featureId,
                scope,
                registeredAt,
                at,
                lastEnd,
                lastError,
                runs + 1,
                failures,
                nextRun,
                interval,
                lastSkippedElsewhere);
    }

    JobStatus succeeded(Instant at) {
        return new JobStatus(
                id,
                featureId,
                scope,
                registeredAt,
                lastStart,
                at,
                null,
                runs,
                failures,
                nextRun,
                interval,
                lastSkippedElsewhere);
    }

    JobStatus failed(Instant at, Throwable cause) {
        return new JobStatus(
                id,
                featureId,
                scope,
                registeredAt,
                lastStart,
                at,
                String.valueOf(cause.getMessage()),
                runs,
                failures + 1,
                nextRun,
                interval,
                lastSkippedElsewhere);
    }

    JobStatus scheduled(Instant next, Duration gap) {
        return new JobStatus(
                id,
                featureId,
                scope,
                registeredAt,
                lastStart,
                lastEnd,
                lastError,
                runs,
                failures,
                next,
                gap,
                lastSkippedElsewhere);
    }

    JobStatus skippedElsewhere(Instant at) {
        return new JobStatus(
                id,
                featureId,
                scope,
                registeredAt,
                lastStart,
                lastEnd,
                lastError,
                runs,
                failures,
                nextRun,
                interval,
                at);
    }

    /**
     * When a run last finished, or another instance was found running it, or when the job was
     * registered if neither has happened. A tick another instance ran counts as completed here.
     */
    public Instant lastCompletedOrRegistered() {
        Instant latest = lastEnd != null ? lastEnd : registeredAt;
        return lastSkippedElsewhere != null && lastSkippedElsewhere.isAfter(latest) ? lastSkippedElsewhere : latest;
    }

    /**
     * Whether no run has finished, here or on another instance, within three of the job's own intervals. Unknown until the
     * scheduler has computed an interval, and a job with none is never called stalled.
     */
    public boolean degraded(Instant now) {
        return interval != null
                && !interval.isZero()
                && now.isAfter(lastCompletedOrRegistered().plus(interval.multipliedBy(3)));
    }
}
