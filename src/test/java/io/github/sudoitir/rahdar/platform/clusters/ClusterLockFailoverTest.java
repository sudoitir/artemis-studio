package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A failover takes the session a lock lives in with it. The lock holder notices, stops the duty, and the
 * duty is taken again by the next pass; the pool itself recovers without a restart.
 */
class ClusterLockFailoverTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl() + "&ApplicationName=lock-test");
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        POSTGRES.stop();
    }

    @Test
    void aLockWhoseConnectionWasKilledStopsItsWorkAndIsTakenAgain() throws Exception {
        ClusterLock lock = new ClusterLock(dataSource);
        UUID cluster = UUID.randomUUID();
        CountDownLatch holding = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean interruptLeftOnTheCaller = new AtomicBoolean();

        Thread caller = Thread.ofPlatform().start(() -> {
            lock.runIfHeld(cluster, () -> {
                holding.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException _) {
                    stopped.set(true);
                }
            });
            interruptLeftOnTheCaller.set(Thread.currentThread().isInterrupted());
        });
        assertThat(holding.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        jdbc.queryForList("""
                SELECT pg_terminate_backend(pid) FROM pg_locks
                WHERE locktype = 'advisory' AND granted AND pid <> pg_backend_pid()
                """);

        await().atMost(Duration.ofSeconds(15)).untilTrue(stopped);
        caller.join(Duration.ofSeconds(10));
        assertThat(caller.isAlive()).isFalse();
        assertThat(interruptLeftOnTheCaller).isFalse();
        assertThat(lock.runIfHeld(cluster, () -> {})).isTrue();
    }

    @Test
    void thePoolRecoversAfterEveryConnectionWasKilled() {
        jdbc.queryForObject("SELECT 1", Integer.class);

        jdbc.queryForList("""
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE application_name = 'lock-test' AND pid <> pg_backend_pid()
                """);

        await().atMost(Duration.ofSeconds(15))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT 1", Integer.class))
                        .isEqualTo(1));
    }
}
