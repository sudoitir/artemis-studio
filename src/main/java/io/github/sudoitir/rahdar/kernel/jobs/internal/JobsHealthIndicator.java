package io.github.sudoitir.rahdar.kernel.jobs.internal;

import io.github.sudoitir.rahdar.kernel.core.RahdarHealth;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatus;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatuses;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code jobs} contributor: degraded when any job has not finished a run within three of
 * its own intervals or whose schedule cannot be read, naming the job and when it last completed or why.
 */
@Component
@RequiredArgsConstructor
class JobsHealthIndicator extends AbstractHealthIndicator {

    private final JobStatuses statuses;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Instant now = Instant.now();
        List<JobStatus> all = statuses.all();
        Map<String, String> stalled = new LinkedHashMap<>();
        for (JobStatus s : all) {
            if (s.invalidSchedule() != null) {
                stalled.put(s.id(), "invalid schedule: " + s.invalidSchedule());
            } else if (s.degraded(now)) {
                stalled.put(
                        s.id(),
                        (s.lastEnd() != null
                                        ? "last completed at " + s.lastEnd()
                                        : "no run completed since " + s.registeredAt())
                                + ", interval " + s.interval());
            }
        }
        builder.status(stalled.isEmpty() ? Status.UP : RahdarHealth.DEGRADED)
                .withDetail("jobs", all.size())
                .withDetail("stalled", stalled);
    }
}
