package io.github.sudoitir.artemisstudio.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Probes and drain (ADR-0148): ready once started, not ready while draining. */
class ReplicaReadinessTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HaProperties ha;

    @Autowired
    WebApplicationContext context;

    @Autowired
    ReplicaRegistry running;

    private final StudioBus bus = Mockito.mock();
    private final ObjectProvider<BuildProperties> builds = Mockito.mock();
    private ReplicaRegistry own;

    @AfterEach
    void clean() {
        if (own != null) {
            own.stop();
            jdbc.update("DELETE FROM studio_replica WHERE id = ?", own.id());
        }
    }

    private ReplicaRegistry replica() {
        own = new ReplicaRegistry(jdbc, ha, builds);
        own.start();
        return own;
    }

    @Test
    void theProbesAnswerOnceTheReplicaIsReady() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        await().atMost(Duration.ofSeconds(30)).until(() -> running.state() == ReplicaRegistry.State.READY);

        mvc.perform(get("/livez")).andExpect(status().isOk());
        mvc.perform(get("/readyz")).andExpect(status().isOk());
    }

    @Test
    void theReplicaIsReadyOnlyWhenReadyAndTheBusListens() {
        ReplicaRegistry replica = replica();
        ReplicaHealthIndicator indicator = new ReplicaHealthIndicator(replica, bus);
        Mockito.when(bus.downSince()).thenReturn(Optional.empty());

        assertThat(indicator.health().getStatus()).as("still starting").isEqualTo(Status.DOWN);

        replica.markReady();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        Mockito.when(bus.downSince()).thenReturn(Optional.of(Instant.now().minusSeconds(5)));
        assertThat(indicator.health().getStatus()).as("a short bus outage").isEqualTo(Status.UP);

        Mockito.when(bus.downSince()).thenReturn(Optional.of(Instant.now().minusSeconds(11)));
        assertThat(indicator.health().getStatus()).as("a long bus outage").isEqualTo(Status.DOWN);
    }

    @Test
    void readinessFailsWhileDrainingAndDrainingTellsEveryone() {
        ReplicaRegistry replica = replica();
        replica.markReady();
        ReplicaHealthIndicator indicator = new ReplicaHealthIndicator(replica, bus);
        Mockito.when(bus.downSince()).thenReturn(Optional.empty());
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;
        ShutdownStep drain = new ReplicaShutdownSteps().drainShutdown(replica, events, ha);
        assertThat(drain.getPhase()).isEqualTo(ShutdownPhases.DRAIN);
        drain.start();

        drain.stop();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(replica.state()).isEqualTo(ReplicaRegistry.State.DRAINING);
        assertThat(published)
                .anySatisfy(e -> assertThat(((AvailabilityChangeEvent<?>) e).getState())
                        .isEqualTo(ReadinessState.REFUSING_TRAFFIC))
                .contains(new DrainStarted());
    }

    @Test
    void drainRunsBeforeEverythingElse() {
        assertThat(ShutdownPhases.DRAIN).isGreaterThan(ShutdownPhases.PLUGINS);
        assertThat(ShutdownPhases.STREAM).isGreaterThan(ShutdownPhases.RUNS);
        assertThat(ShutdownPhases.RUNS).isGreaterThan(ShutdownPhases.JOBS);
        assertThat(ShutdownPhases.DRAIN)
                .as("above the web server's graceful shutdown, so connections are still accepted")
                .isGreaterThan(Integer.MAX_VALUE - 1024);
    }
}
