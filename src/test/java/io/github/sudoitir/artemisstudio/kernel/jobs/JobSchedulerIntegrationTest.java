package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances on one database (operational-health spec, ADR-0125): each is a {@link JobStatuses}
 * with its own lock executor over the shared {@code shedlock} table, as two Studio processes would
 * have. An installation-wide job runs on one of them per tick; an instance job runs on both.
 */
class JobSchedulerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    LockingTaskExecutor jobLockExecutor;

    private JobStatuses instance() {
        return new JobStatuses(jobLockExecutor, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private LockingTaskExecutor otherProcess() {
        return new DefaultLockingTaskExecutor(
                new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()));
    }

    @Test
    void anInstallationWideJobRunsOnOneInstancePerTick() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable task = () -> {
            ran.incrementAndGet();
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        String id = "it-installation-" + System.nanoTime();
        JobStatuses first = instance();
        JobStatuses second = instance();
        Runnable onFirst = first.instrument(ScheduledJob.fixedDelay(
                id, "jobs", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(30), task));
        Runnable onSecond = second.instrument(ScheduledJob.fixedDelay(
                id, "jobs", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(30), task));

        Thread running = Thread.ofVirtual().start(onFirst);
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        onSecond.run(); // while the first holds the lock
        release.countDown();
        running.join();
        onSecond.run(); // the same tick, just after the first finished: its minimum gap still holds

        assertThat(ran).hasValue(1);
        assertThat(second.all().getFirst().lastSkippedElsewhere()).isNotNull();
        assertThat(second.all().getFirst().runs()).isZero();
    }

    @Test
    void anInstanceJobRunsOnEveryInstance() {
        AtomicInteger ran = new AtomicInteger();
        String id = "it-instance-" + System.nanoTime();
        instance()
                .instrument(ScheduledJob.fixedDelay(
                        id, "jobs", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(30), ran::incrementAndGet))
                .run();
        instance()
                .instrument(ScheduledJob.fixedDelay(
                        id, "jobs", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(30), ran::incrementAndGet))
                .run();
        assertThat(ran).hasValue(2);
    }

    @Test
    void aCrashedHolderFreesTheJobWhenItsLockRunsOut() throws Exception {
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
