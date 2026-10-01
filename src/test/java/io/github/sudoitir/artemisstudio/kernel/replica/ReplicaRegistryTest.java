package io.github.sudoitir.artemisstudio.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry.State;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The registry: this process is live, a stopped one is not a crash, a silent one is. */
class ReplicaRegistryTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReplicaRegistry running;

    @Autowired
    HaProperties ha;

    private final ObjectProvider<BuildProperties> builds = Mockito.mock();
    private ReplicaRegistry own;

    @AfterEach
    void clean() {
        if (own != null) {
            own.stop();
            jdbc.update("DELETE FROM studio_replica WHERE id = ?", own.id());
        }
    }

    private ReplicaRegistry start() {
        own = new ReplicaRegistry(jdbc, ha, builds);
        own.start();
        return own;
    }

    private void insert(UUID id, String state, String stoppedAt, String heartbeatAge) {
        jdbc.update(
                "INSERT INTO studio_replica (started_at, heartbeat_at, stopped_at, host, version, state, id)"
                        + " VALUES (now() - interval '1 hour', now() - ?::interval, " + stoppedAt
                        + ", 'test', 'test', ?, ?)",
                heartbeatAge,
                state,
                id);
    }

    @Test
    void theRunningApplicationIsLive() {
        assertThat(running.live()).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(running.id());
            assertThat(r.host()).isNotBlank();
        });
    }

    @Test
    void aReplicaIsLiveWhileItHeartbeatsAndNotAfterItDrainsOrStops() {
        ReplicaRegistry replica = start();
        assertThat(replica.live()).extracting(ReplicaRegistry.Replica::id).contains(replica.id());
        assertThat(replica.state()).isEqualTo(State.STARTING);

        replica.markReady();
        assertThat(replica.live())
                .filteredOn(r -> r.id().equals(replica.id()))
                .singleElement()
                .extracting(ReplicaRegistry.Replica::state)
                .isEqualTo(State.READY);

        replica.markDraining();
        assertThat(replica.live()).extracting(ReplicaRegistry.Replica::id).doesNotContain(replica.id());

        replica.markReady();
        assertThat(replica.state())
                .as("a draining replica does not become ready again")
                .isEqualTo(State.DRAINING);
    }

    @Test
    void aStoppedReplicaIsNotACrashAndASilentOneIs() {
        UUID crashed = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        insert(crashed, "ready", "NULL", "1 minute");
        insert(stopped, "stopped", "now() - interval '1 minute'", "1 minute");
        insert(healthy, "ready", "NULL", "1 second");
        try {
            long before = running.crashesSince(Duration.ofMinutes(15));
            assertThat(before).isGreaterThanOrEqualTo(1);
            assertThat(running.live()).extracting(ReplicaRegistry.Replica::id).contains(healthy);

            jdbc.update("DELETE FROM studio_replica WHERE id = ?", crashed);
            assertThat(running.crashesSince(Duration.ofMinutes(15))).isEqualTo(before - 1);
            assertThat(running.crashesSince(Duration.ofSeconds(30)))
                    .as("the window counts from the last heartbeat")
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM studio_replica WHERE id IN (?, ?, ?)", crashed, stopped, healthy);
        }
    }

    @Test
    void aliveCountsADrainingReplicaButNotAStoppedOrSilentOne() {
        UUID draining = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        UUID silent = UUID.randomUUID();
        insert(draining, "draining", "NULL", "1 second");
        insert(stopped, "stopped", "now()", "1 second");
        insert(silent, "ready", "NULL", "1 hour");
        try {
            assertThat(running.aliveIds()).contains(running.id(), draining).doesNotContain(stopped, silent);
        } finally {
            jdbc.update("DELETE FROM studio_replica WHERE id IN (?, ?, ?)", draining, stopped, silent);
        }
    }

    @Test
    void stoppingRecordsTheStop() {
        ReplicaRegistry replica = start();

        replica.stop();

        assertThat(jdbc.queryForObject(
                        "SELECT state || ' ' || (stopped_at IS NOT NULL) FROM studio_replica WHERE id = ?",
                        String.class,
                        replica.id()))
                .isEqualTo("stopped true");
        assertThat(replica.live()).extracting(ReplicaRegistry.Replica::id).doesNotContain(replica.id());
    }

    @Test
    void theHeartbeatThreadKeepsTheReplicaAlive() {
        own = new ReplicaRegistry(
                jdbc,
                new HaProperties(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO),
                builds);
        own.start();
        await().during(Duration.ofMillis(1500))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(own.live())
                        .extracting(ReplicaRegistry.Replica::id)
                        .contains(own.id()));
    }

    @Test
    void theHeartbeatIsFreshUntilItHasFailedForLongerThanTheTtl() {
        JdbcTemplate flaky = spy(jdbc);
        AtomicBoolean down = new AtomicBoolean();
        doAnswer(call -> {
                    if (down.get()) {
                        throw new DataAccessResourceFailureException("database unreachable");
                    }
                    return call.callRealMethod();
                })
                .when(flaky)
                .update(anyString(), any(Object[].class));
        own = new ReplicaRegistry(
                flaky,
                new HaProperties(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ZERO, Duration.ZERO),
                builds);
        own.start();
        await().atMost(Duration.ofSeconds(2)).until(own::heartbeatFresh);

        down.set(true);

        await().atMost(Duration.ofSeconds(5)).until(() -> !own.heartbeatFresh());
        down.set(false);
        await().atMost(Duration.ofSeconds(5)).until(own::heartbeatFresh);
    }
}
