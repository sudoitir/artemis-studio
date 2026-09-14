package io.github.sudoitir.artemisstudio.kernel.jobs.internal;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatus;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * The {@code jobs} contributor: degraded when any job has not finished a run within three of
 * its own intervals, naming the job and when it last completed.
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
            if (s.degraded(now)) {
                stalled.put(
                        s.id(),
                        (s.lastEnd() != null
                                        ? "last completed at " + s.lastEnd()
                                        : "no run completed since " + s.registeredAt())
                                + ", interval " + s.interval());
            }
        }
        builder.status(
                        stalled.isEmpty()
                                ? org.springframework.boot.health.contributor.Status.UP
                                : StudioHealth.DEGRADED)
                .withDetail("jobs", all.size())
                .withDetail("stalled", stalled);
    }
}
