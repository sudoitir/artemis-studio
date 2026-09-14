package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

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

    @Test
    void theTriggerRecordsTheNextRunAndItsInterval() {
        ScheduledJob job = ScheduledJob.fixedDelay("tick", "rr", () -> Duration.ofSeconds(15), () -> {});
        statuses.instrument(job);

        Instant next = statuses.trigger(job).nextExecution(new SimpleTriggerContext());

        JobStatus s = statuses.all().getFirst();
        assertThat(s.nextRun()).isEqualTo(next);
        assertThat(s.interval()).isBetween(Duration.ofSeconds(14), Duration.ofSeconds(15));
    }

    @Test
    void aJobThatHasNotFinishedWithinThreeIntervalsIsDegraded() {
        Instant registered = Instant.parse("2026-09-13T10:00:00Z");
        JobStatus s = new JobStatus("tick", "rr", registered, null, null, null, 0, 0, null, Duration.ofSeconds(15));

        assertThat(s.degraded(registered.plusSeconds(44))).isFalse();
        assertThat(s.degraded(registered.plusSeconds(46))).isTrue();

        JobStatus finished = s.started(registered.plusSeconds(40)).succeeded(registered.plusSeconds(41));
        assertThat(finished.degraded(registered.plusSeconds(46))).isFalse();
        assertThat(finished.degraded(registered.plusSeconds(87))).isTrue();
    }

    @Test
    void aJobWithoutAKnownIntervalIsNeverCalledStalled() {
        JobStatus s = new JobStatus("tick", "rr", Instant.EPOCH, null, null, null, 0, 0, null, null);
        assertThat(s.degraded(Instant.now())).isFalse();
    }
}
