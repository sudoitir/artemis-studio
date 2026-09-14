package io.github.sudoitir.artemisstudio.kernel.jobs.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatus;
import io.github.sudoitir.artemisstudio.kernel.jobs.JobStatuses;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/system/jobs}: every background job's status (operational-health spec). */
@RestController
@RequiredArgsConstructor
public class JobsController {

    private final JobStatuses statuses;

    public record JobStatusView(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(requiredMode = REQUIRED, description = "The module that owns the job.")
            String featureId,

            @Schema(nullable = true) Instant lastStart,
            @Schema(nullable = true) Instant lastEnd,

            @Schema(nullable = true, description = "The most recent run's failure; absent once a later run succeeds.")
            String lastError,

            @Schema(requiredMode = REQUIRED) long runs,
            @Schema(requiredMode = REQUIRED) long failures,

            @Schema(nullable = true, description = "When the scheduler next intends to start it.")
            Instant nextRun,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether no run has finished within three of the job's intervals.")
            boolean degraded) {

        static JobStatusView of(JobStatus s, Instant now) {
            return new JobStatusView(
                    s.id(),
                    s.featureId(),
                    s.lastStart(),
                    s.lastEnd(),
                    s.lastError(),
                    s.runs(),
                    s.failures(),
                    s.nextRun(),
                    s.degraded(now));
        }
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions).SETTINGS_READ)")
    @GetMapping("/api/v1/system/jobs")
    public List<JobStatusView> jobs() {
        Instant now = Instant.now();
        return statuses.all().stream().map(s -> JobStatusView.of(s, now)).toList();
    }
}
