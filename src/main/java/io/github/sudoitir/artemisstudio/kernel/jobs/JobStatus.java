package io.github.sudoitir.artemisstudio.kernel.jobs;

import java.time.Instant;

/**
 * What is known about one job's runs since Studio started (operational-health spec).
 *
 * @param lastError the message of the most recent run's failure; {@code null} once a later run succeeds
 * @param runs every started run, successful or not
 */
public record JobStatus(
        String id, String featureId, Instant lastStart, Instant lastEnd, String lastError, long runs, long failures) {

    static JobStatus never(ScheduledJob job) {
        return new JobStatus(job.id(), job.featureId(), null, null, null, 0, 0);
    }

    JobStatus started(Instant at) {
        return new JobStatus(id, featureId, at, lastEnd, lastError, runs + 1, failures);
    }

    JobStatus succeeded(Instant at) {
        return new JobStatus(id, featureId, lastStart, at, null, runs, failures);
    }

    JobStatus failed(Instant at, Throwable cause) {
        return new JobStatus(id, featureId, lastStart, at, String.valueOf(cause.getMessage()), runs, failures + 1);
    }
}
