package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances on one database (operational-health spec, ADR-0125): each is a {@link JobRunner} with its own
 * {@link JobStatuses} over the shared {@code shedlock} table, as two Studio processes would have. An
 * installation-wide job runs on one of them per tick; an instance job runs on both.
 */
class JobRunnerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    LockProvider jobLockProvider;

    private final List<JobRunner> started = new ArrayList<>();

    private static final class Instance {
        final JobStatuses statuses = new JobStatuses(new SimpleMeterRegistry());
        JobRunner runner;
    }

    private Instance instance() {
        Instance instance = new Instance();
        instance.runner = new JobRunner(List.of(), instance.statuses, jobLockProvider, ObservationRegistry.NOOP);
        started.add(instance.runner);
        return instance;
    }

    @AfterEach
    void stop() throws Exception {
        for (JobRunner runner : started) {
            runner.destroy();
        }
    }

    /** A job that is due once, now, and then not for an hour. */
    private static ScheduledJob onceNow(String id, ScheduledJob.Scope scope, Runnable task) {
        return new ScheduledJob(
                id,
                "jobs",
                scope,
                context -> context.lastCompletion() == null
                        ? Instant.now()
                        : Instant.now().plus(Duration.ofHours(1)),
                () -> Duration.ofSeconds(30),
                () -> Duration.ofMinutes(5),
                task);
    }

    private LockingTaskExecutor otherProcess() {
        return new DefaultLockingTaskExecutor(
                new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()));
    }

    @Test
    void anInstallationWideJobRunsOnOneInstancePerTick() {
        AtomicInteger ran = new AtomicInteger();
        String id = "it-installation-" + System.nanoTime();
        Instance first = instance();
        Instance second = instance();
        first.runner.schedule(onceNow(id, ScheduledJob.Scope.INSTALLATION, ran::incrementAndGet));
        second.runner.schedule(onceNow(id, ScheduledJob.Scope.INSTALLATION, ran::incrementAndGet));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> ran.get() == 1
                        && (first.statuses.get(id).lastSkippedElsewhere() != null
                                || second.statuses.get(id).lastSkippedElsewhere() != null));

        Instance skipped = first.statuses.get(id).lastSkippedElsewhere() != null ? first : second;
        assertThat(ran).hasValue(1);
        assertThat(skipped.statuses.get(id).runs()).isZero();
    }

    @Test
    void aLongRunKeepsItsLockPastItsLifetimeByExtendingIt() throws Exception {
        String id = "it-extend-" + System.nanoTime();
        AtomicInteger ran = new AtomicInteger();
        // The lock lives one second; the runner extends it every half second while the run lasts 2.5 s.
        Instance holder = instance();
        holder.runner.schedule(new ScheduledJob(
                id,
                "jobs",
                ScheduledJob.Scope.INSTALLATION,
                context -> context.lastCompletion() == null
                        ? Instant.now()
                        : Instant.now().plus(Duration.ofHours(1)),
                () -> Duration.ofSeconds(1),
                () -> Duration.ofMinutes(5),
                () -> {
                    ran.incrementAndGet();
                    try {
                        Thread.sleep(2_500);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));
        Instant late = Instant.now().plusMillis(1_800);
        Instance other = instance();
        other.runner.schedule(new ScheduledJob(
                id,
                "jobs",
                ScheduledJob.Scope.INSTALLATION,
                context ->
                        context.lastCompletion() == null ? late : Instant.now().plus(Duration.ofHours(1)),
                () -> Duration.ofSeconds(1),
                () -> Duration.ofMinutes(5),
                ran::incrementAndGet));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> other.statuses.get(id).lastSkippedElsewhere() != null);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> holder.statuses.get(id).lastEnd() != null);

        assertThat(ran).hasValue(1);
        assertThat(holder.statuses.get(id).lockLost()).isZero();
    }

    @Test
    void anInstanceJobRunsOnEveryInstance() {
        AtomicInteger ran = new AtomicInteger();
        String id = "it-instance-" + System.nanoTime();
        instance().runner.schedule(onceNow(id, ScheduledJob.Scope.INSTANCE, ran::incrementAndGet));
        instance().runner.schedule(onceNow(id, ScheduledJob.Scope.INSTANCE, ran::incrementAndGet));

        await().atMost(Duration.ofSeconds(10)).until(() -> ran.get() == 2);
    }

    @Test
    void aCrashedHolderFreesTheJobWhenItsLockRunsOut() {
        String id = "it-crash-" + System.nanoTime();
        // A process that took the lock and died: nothing releases it; it lapses at lockAtMostFor.
        var crashed = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build())
                .lock(new LockConfiguration(Instant.now(), id, Duration.ofSeconds(2), Duration.ZERO));
        assertThat(crashed).isPresent();

        AtomicInteger ran = new AtomicInteger();
        LockConfiguration config = new LockConfiguration(Instant.now(), id, Duration.ofSeconds(60), Duration.ZERO);
        otherProcess().executeWithLock((Runnable) ran::incrementAndGet, config);
        assertThat(ran).hasValue(0);

        await("the crashed holder's lock lapses")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    otherProcess().executeWithLock((Runnable) ran::incrementAndGet, config);
                    return ran.get() > 0;
                });
        assertThat(ran).hasValue(1);
    }
}
