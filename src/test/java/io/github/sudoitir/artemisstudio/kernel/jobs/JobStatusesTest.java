package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JobStatusesTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JobStatuses statuses = new JobStatuses(meters);

    @Test
    void everyRunIsRecordedAndAFailureIsRethrown() {
        AtomicBoolean fail = new AtomicBoolean();
        Runnable run = statuses.instrument(ScheduledJob.fixedDelay("demo", "rr", () -> Duration.ofSeconds(1), () -> {
            if (fail.get()) {
                throw new IllegalStateException("boom");
            }
        }));

        run.run();
        JobStatus ok = statuses.all().getFirst();
        assertThat(ok.runs()).isEqualTo(1);
        assertThat(ok.failures()).isZero();
        assertThat(ok.lastError()).isNull();
        assertThat(ok.lastEnd()).isNotNull();

        fail.set(true);
        assertThatThrownBy(run::run).hasMessage("boom");
        JobStatus failed = statuses.all().getFirst();
        assertThat(failed.runs()).isEqualTo(2);
        assertThat(failed.failures()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("boom");

        assertThat(meters.find("studio.job")
                        .tags("job", "demo", "feature", "rr")
                        .timer()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void aDuplicateJobIdIsRefused() {
        ScheduledJob job = ScheduledJob.fixedDelay("dup", "rr", () -> Duration.ofSeconds(1), () -> {});
        statuses.instrument(job);
        assertThatThrownBy(() -> statuses.instrument(job)).hasMessageContaining("'dup'");
    }
}
