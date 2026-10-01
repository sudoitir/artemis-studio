package io.github.sudoitir.artemisstudio.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.platform.clusters.EnvironmentService;
import io.github.sudoitir.artemisstudio.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.GovernanceRuleService;
import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.RuleRequest;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Two replicas, this context (A) and a second one on the same database (B): a change made on A to
 * shared state is served by B within the two seconds the spec allows, and a signal sent on the bus
 * closes the streams of an ended session or revoked token on B.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class CacheCoherenceTest extends PostgresIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(2);

    private static ConfigurableApplicationContext other;

    @Autowired
    SettingsService settingsA;

    @Autowired
    EnvironmentService environmentsA;

    @Autowired
    GovernanceRuleService rulesA;

    @Autowired
    DiagnosticsService diagnosticsA;

    @Autowired
    StudioBus busA;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void startTheOtherReplica() {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        other = new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    @AfterAll
    static void stopTheOtherReplica() {
        other.close();
    }

    @Test
    void aSettingChangedOnOneReplicaIsServedByTheOtherAndPushedToItsHolders() {
        SettingsService settingsB = other.getBean(SettingsService.class);
        NodeCallLimiter limiterB = other.getBean(NodeCallLimiter.class);

        settingsA.put(BrokerSettings.RATE_LIMIT, "7");
        await().atMost(WITHIN).untilAsserted(() -> {
            assertThat(settingsB.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(7);
            assertThat(limiterB.permitsPerSecond()).isEqualTo(7);
        });

        settingsA.reset(BrokerSettings.RATE_LIMIT);
        await().atMost(WITHIN).untilAsserted(() -> {
            assertThat(settingsB.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(20);
            assertThat(limiterB.permitsPerSecond()).isEqualTo(20);
        });
    }

    @Test
    void aClusterMovedToAnotherEnvironmentOnOneReplicaIsSeenByTheOther() {
        ClusterEnvironmentIndex indexB = other.getBean(ClusterEnvironmentIndex.class);
        UUID clusterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO cluster (id, name) VALUES (:id, :name)",
                Map.of("id", clusterId, "name", "coherence-" + clusterId));
        UUID environmentId = environmentsA
                .create(new EnvironmentRequest("coherence-" + clusterId, null, 0))
                .id();
        try {
            assertThat(indexB.environmentOf(clusterId)).isNull();

            environmentsA.assignCluster(clusterId, environmentId);

            await().atMost(WITHIN)
                    .untilAsserted(
                            () -> assertThat(indexB.environmentOf(clusterId)).isEqualTo(environmentId));
        } finally {
            jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
            environmentsA.delete(environmentId);
        }
    }

    @Test
    void aGovernanceRuleWrittenOnOneReplicaChangesThePolicyOfTheOther() {
        ContentPolicy policyB = other.getBean(ContentPolicy.class);
        int before = policyB.version();

        UUID rule = rulesA.create(
                        new RuleRequest(null, "PROPERTY", "coherence-" + UUID.randomUUID(), "PERSONAL", null, true))
                .id();

        await().atMost(WITHIN).untilAsserted(() -> assertThat(policyB.version()).isGreaterThan(before));
        rulesA.delete(rule);
    }

    @Test
    void aSupportBundlePreparedOnOneReplicaCanBeDownloadedFromTheOther() {
        DiagnosticsService diagnosticsB = other.getBean(DiagnosticsService.class);

        UUID id = diagnosticsA.prepare().id();

        assertThat(diagnosticsB.take(id, List.of("about")).sections())
                .singleElement()
                .satisfies(section -> assertThat(section.key()).isEqualTo("about"));
    }

    @Test
    void anEndedSessionClosesItsStreamsOnTheOtherReplica() {
        SseEmitter ended = mock(SseEmitter.class);
        SseEmitter kept = mock(SseEmitter.class);
        SseHub hubB = other.getBean(SseHub.class);
        UUID clusterId = UUID.randomUUID();
        hubB.register(clusterId, new Subscriber(ended, Set.of("queues"), "session-ended-1"));
        hubB.register(clusterId, new Subscriber(kept, Set.of("queues"), "session-kept"));

        busA.publish(new ReplicaSignal("session-ended", "session-ended-1"));

        await().atMost(WITHIN).untilAsserted(() -> verify(ended).complete());
        assertThat(hubB.clientCount()).isEqualTo(1);
    }

    @Test
    void aRevokedTokenClosesItsStreamsOnTheOtherReplica() {
        SseEmitter revoked = mock(SseEmitter.class);
        SseHub hubB = other.getBean(SseHub.class);
        UUID tokenId = UUID.randomUUID();
        hubB.register(UUID.randomUUID(), new Subscriber(revoked, Set.of("queues"), null, tokenId));

        busA.publish(new ReplicaSignal("token-revoked", tokenId.toString()));

        await().atMost(WITHIN).untilAsserted(() -> verify(revoked).complete());
    }
}
