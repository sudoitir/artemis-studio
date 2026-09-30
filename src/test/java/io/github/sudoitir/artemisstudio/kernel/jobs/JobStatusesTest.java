package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class JobStatusesTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicBoolean heldElsewhere = new AtomicBoolean();
    private final JobStatuses statuses = new JobStatuses(
            new DefaultLockingTaskExecutor(config -> heldElsewhere.get() ? Optional.empty() : Optional.of(() -> {})),
            observationsInto(meters),
            meters);

    private static io.micrometer.observation.ObservationRegistry observationsInto(SimpleMeterRegistry meters) {
        io.micrometer.observation.ObservationRegistry registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(
                        new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        return registry;
    }

    @Test
    void anInstallationWideJobRunsOnlyWhileItHoldsItsLock() {
        AtomicInteger ran = new AtomicInteger();
        Runnable run = statuses.instrument(ScheduledJob.fixedDelay(
                "shared", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(10), ran::incrementAndGet));

        run.run();
        assertThat(ran).hasValue(1);
        assertThat(statuses.all().getFirst().runs()).isEqualTo(1);

        heldElsewhere.set(true);
        run.run();
        JobStatus skipped = statuses.all().getFirst();
        assertThat(ran).hasValue(1);
        assertThat(skipped.runs()).isEqualTo(1);
        assertThat(skipped.lastSkippedElsewhere()).isNotNull();
        assertThat(skipped.scope()).isEqualTo(ScheduledJob.Scope.INSTALLATION);
    }

    @Test
    void aTickAnotherInstanceRanDoesNotMakeTheJobDegraded() {
        heldElsewhere.set(true);
        ScheduledJob job = ScheduledJob.fixedDelay(
                "elsewhere", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(1), () -> {});
        Runnable run = statuses.instrument(job);
        statuses.trigger(job).nextExecution(new SimpleTriggerContext());

        run.run();

        JobStatus status = statuses.all().getFirst();
        assertThat(status.lastEnd()).isNull();
        assertThat(status.degraded(status.lastSkippedElsewhere().plusMillis(2_900)))
                .isFalse();
        assertThat(status.degraded(status.lastSkippedElsewhere().plusSeconds(4)))
                .isTrue();
    }

    @Test
    void anInstanceJobIgnoresTheLock() {
        heldElsewhere.set(true);
        AtomicInteger ran = new AtomicInteger();
        statuses.instrument(ScheduledJob.fixedDelay(
                        "local", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(1), ran::incrementAndGet))
                .run();
        assertThat(ran).hasValue(1);
    }

    @Test
    void theMinimumGapIsMostOfTheIntervalAndAtMostFiveMinutes() {
        assertThat(ScheduledJob.fixedDelay(
                                "a", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(10), () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofSeconds(9));
        assertThat(ScheduledJob.fixedDelay(
                                "b", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofHours(1), () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledJob.cron("c", "rr", ScheduledJob.Scope.INSTALLATION, () -> "0 0 4 * * *", () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void everyRunIsRecordedAndAFailureIsRethrown() {
        AtomicBoolean fail = new AtomicBoolean();
        Runnable run = statuses.instrument(
                ScheduledJob.fixedDelay("demo", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(1), () -> {
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

        // The observation adds an `error` tag, so a failed run lands on its own timer beside the passing ones.
        assertThat(meters.find("studio.job").tags("job", "demo", "feature", "rr").timers().stream()
                        .mapToLong(io.micrometer.core.instrument.Timer::count)
                        .sum())
                .isEqualTo(2);
    }

    @Test
    void aDuplicateJobIdIsRefused() {
        ScheduledJob job = ScheduledJob.fixedDelay(
                "dup", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(1), () -> {});
        statuses.instrument(job);
        assertThatThrownBy(() -> statuses.instrument(job)).hasMessageContaining("'dup'");
    }

    @Test
    void theTriggerRecordsTheNextRunAndItsInterval() {
        ScheduledJob job = ScheduledJob.fixedDelay(
                "tick", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(15), () -> {});
        statuses.instrument(job);

        Instant next = statuses.trigger(job).nextExecution(new SimpleTriggerContext());

        JobStatus s = statuses.all().getFirst();
        assertThat(s.nextRun()).isEqualTo(next);
        assertThat(s.interval()).isBetween(Duration.ofSeconds(14), Duration.ofSeconds(15));
    }

    @Test
    void aJobThatHasNotFinishedWithinThreeIntervalsIsDegraded() {
        Instant registered = Instant.parse("2026-09-13T10:00:00Z");
        JobStatus s = new JobStatus(
                "tick",
                "rr",
                ScheduledJob.Scope.INSTANCE,
                registered,
                null,
                null,
                null,
                0,
                0,
                null,
                Duration.ofSeconds(15),
                null);

        assertThat(s.degraded(registered.plusSeconds(44))).isFalse();
        assertThat(s.degraded(registered.plusSeconds(46))).isTrue();

        JobStatus finished = s.started(registered.plusSeconds(40)).succeeded(registered.plusSeconds(41));
        assertThat(finished.degraded(registered.plusSeconds(46))).isFalse();
        assertThat(finished.degraded(registered.plusSeconds(87))).isTrue();
    }

    @Test
    void aJobWithoutAKnownIntervalIsNeverCalledStalled() {
        JobStatus s = new JobStatus(
                "tick", "rr", ScheduledJob.Scope.INSTANCE, Instant.EPOCH, null, null, null, 0, 0, null, null, null);
        assertThat(s.degraded(Instant.now())).isFalse();
    }

    @Test
    void lagIsTheTimePastTheIntervalAndNeverNegative() {
        Instant done = Instant.parse("2026-01-01T00:00:00Z");
        JobStatus status = new JobStatus(
                "poll",
                "scrape",
                ScheduledJob.Scope.INSTANCE,
                done,
                done,
                done,
                null,
                1,
                0,
                null,
                Duration.ofSeconds(10),
                null);

        assertThat(status.lag(done.plusSeconds(4))).isEqualTo(Duration.ZERO);
        assertThat(status.lag(done.plusSeconds(25))).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void lagIsUnknownBeforeTheSchedulerHasComputedAnInterval() {
        statuses.instrument(ScheduledJob.fixedDelay(
                "fresh", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(10), () -> {}));

        assertThat(statuses.all().getFirst().lag(Instant.now())).isNull();
        assertThat(meters.get("studio.job.lag").tag("job", "fresh").gauge().value())
                .isNaN();
    }

    @Test
    void deregisteringAJobRemovesItsLagGauge() {
        statuses.instrument(ScheduledJob.fixedDelay(
                "gone", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(10), () -> {}));
        statuses.deregister("gone");

        assertThat(meters.find("studio.job.lag").tag("job", "gone").gauge()).isNull();
    }
}
