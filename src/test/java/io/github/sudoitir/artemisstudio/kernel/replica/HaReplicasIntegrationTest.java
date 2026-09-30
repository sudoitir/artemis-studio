package io.github.sudoitir.artemisstudio.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterOwnership;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Two complete replicas of one installation: two application contexts on one database and one real
 * broker, with the real {@link ClusterOwnership} (the integration base classes mock it) and timings
 * shortened about fifteen-fold, so the spec's 20 s takeover is a couple of seconds here. Fan-out,
 * replay and cache coherence are {@code EventStreamBusTest} and {@code CacheCoherenceTest}; this
 * is what they cannot show, the broker side of a second replica:
 * <ul>
 *   <li>every cluster has exactly one owner and both replicas own some;
 *   <li>a second replica does not add management load on the broker;
 *   <li>when a replica leaves, the other takes its clusters and scrapes them.
 * </ul>
 * The tests run in order and share the two replicas.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HaReplicasIntegrationTest {

    /** Enough that one replica owning none of them by chance is a 1 in 500 draw. */
    private static final int CLUSTERS = 10;

    private static final Duration WINDOW = Duration.ofSeconds(6);
    private static final String MANAGEMENT_CALLS = "studio.broker.management";

    /** Base64 of exactly 32 bytes. */
    private static final String SECRET_KEY = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";

    /**
     * Its own database: replicas of the shared one would include every context the suite has cached,
     * and those mock their ownership, so the leases they are dealt would never be renewed.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withCommand("postgres", "-c", "max_connections=200");

    private static ConfigurableApplicationContext a;
    private static ConfigurableApplicationContext b;
    private static JdbcTemplate jdbc;
    private static final List<UUID> clusters = new ArrayList<>();

    /** The management calls one replica alone made to the broker in {@link #WINDOW}. */
    private static long oneReplicaCalls;

    @BeforeAll
    static void startOneReplicaAndRegisterTheClusters() {
        // The broker's container starts while the first replica boots.
        CompletableFuture<String> broker = CompletableFuture.supplyAsync(ArtemisIntegrationTest::jolokiaUrl);
        POSTGRES.start();
        a = replica();
        jdbc = a.getBean(JdbcTemplate.class);

        new AdminAuthenticationExtension().beforeEach(null);
        ClusterService service = a.getBean(ClusterService.class);
        for (int i = 0; i < CLUSTERS; i++) {
            var registered = service.register(new RegisterClusterRequest(
                    List.of(broker.join()),
                    "ha-replicas-" + i,
                    null,
                    new RegisterClusterRequest.Credentials(
                            ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                    null,
                    null));
            if (!(registered instanceof Attempt.Ok<ClusterDetail> ok)) {
                throw new IllegalStateException("could not register the container broker: " + registered);
            }
            clusters.add(ok.value().id());
        }
        new AdminAuthenticationExtension().afterEach(null);

        await("the only replica owns every cluster")
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(owned(a)).containsExactlyInAnyOrderElementsOf(clusters));
        awaitScraped(Instant.now());
        oneReplicaCalls = managementCalls(a, WINDOW);

        b = replica();
    }

    @AfterAll
    static void stopTheReplicas() {
        for (ConfigurableApplicationContext context : new ConfigurableApplicationContext[] {b, a}) {
            if (context != null) {
                context.close();
            }
        }
        POSTGRES.stop();
    }

    private static ConfigurableApplicationContext replica() {
        return new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--artemis-studio.secret-key=" + SECRET_KEY,
                        "--server.port=0",
                        "--artemis-studio.ha.heartbeat=300ms",
                        "--artemis-studio.ha.ttl=1s",
                        "--artemis-studio.ha.drain-delay=0s",
                        "--artemis-studio.ha.run-grace=0s",
                        // Brief, but far enough apart that the ten clusters on one node stay under its rate limit.
                        "--artemis-studio.scrape.tier-a-interval=2s",
                        "--artemis-studio.scrape.tier-b-interval=4s");
    }

    private static Set<UUID> owned(ConfigurableApplicationContext replica) {
        Set<UUID> mine = new HashSet<>(replica.getBean(ClusterOwnership.class).owned());
        mine.retainAll(clusters);
        return mine;
    }

    /** Every cluster has had a tier-A pass since {@code since}: each one's node was seen after it. */
    private static void awaitScraped(Instant since) {
        await("every cluster is scraped")
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(unscrapedSince(since)).isEmpty());
    }

    private static List<UUID> unscrapedSince(Instant since) {
        return jdbc.queryForList("""
                SELECT c.id FROM cluster c
                WHERE c.id = ANY (?) AND NOT EXISTS (
                    SELECT 1 FROM broker_node n WHERE n.cluster_id = c.id AND n.last_seen_at > ?)
                """, UUID.class, clusters.toArray(UUID[]::new), java.sql.Timestamp.from(since));
    }

    private static long managementCalls(ConfigurableApplicationContext replica) {
        return replica.getBean(MeterRegistry.class).find(MANAGEMENT_CALLS).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    /** What the replicas together ask of the broker in the window; the test thread waits it out. */
    private static long managementCalls(ConfigurableApplicationContext first, Duration window) {
        List<ConfigurableApplicationContext> replicas = b == null ? List.of(first) : List.of(a, b);
        long before = replicas.stream()
                .mapToLong(HaReplicasIntegrationTest::managementCalls)
                .sum();
        try {
            Thread.sleep(window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return replicas.stream()
                        .mapToLong(HaReplicasIntegrationTest::managementCalls)
                        .sum()
                - before;
    }

    @Test
    @Order(1)
    void everyClusterHasOneOwnerAndBothReplicasOwnSome() {
        await("both replicas are ready and the clusters are spread")
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(owned(a)).isNotEmpty();
                    assertThat(owned(b)).isNotEmpty();
                    Set<UUID> both = new HashSet<>(owned(a));
                    both.addAll(owned(b));
                    assertThat(both).containsExactlyInAnyOrderElementsOf(clusters);
                    assertThat(owned(a)).doesNotContainAnyElementsOf(owned(b));
                });
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT cluster_id) FROM cluster_lease WHERE cluster_id = ANY (?)",
                        Long.class,
                        (Object) clusters.toArray(UUID[]::new)))
                .isEqualTo(CLUSTERS);
    }

    @Test
    @Order(2)
    void aSecondReplicaDoesNotAddManagementLoadOnTheBroker() {
        awaitScraped(Instant.now());

        long twoReplicasCalls = managementCalls(a, WINDOW);

        assertThat(oneReplicaCalls).as("calls of one replica alone").isGreaterThan(20);
        // Duplicated scraping would double the count; the margin absorbs where a tier falls in the window.
        assertThat(twoReplicasCalls)
                .as("calls of two replicas together, one alone made %d", oneReplicaCalls)
                .isLessThanOrEqualTo((long) (oneReplicaCalls * 1.5));
    }

    @Test
    @Order(3)
    void whenOneReplicaLeavesTheOtherTakesItsClustersAndScrapesThem() {
        Set<UUID> theirs = owned(a);
        assertThat(theirs).isNotEmpty();
        Instant leftAt = Instant.now();

        a.close();
        jdbc = b.getBean(JdbcTemplate.class);

        // The spec hands a stopping replica's clusters over within one second; a few more for this test's scheduling.
        await("the other replica owns every cluster")
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(owned(b)).containsExactlyInAnyOrderElementsOf(clusters));
        // ...and its tier A reaches the clusters it was just given: a node's last-seen time moves past the hand-over.
        awaitScraped(leftAt);
    }
}
