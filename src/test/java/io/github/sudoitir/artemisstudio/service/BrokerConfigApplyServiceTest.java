package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigApplyEntity.Outcome;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.NodeApply;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.StepApply;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.StepStatus;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.Verification;
import io.github.sudoitir.artemisstudio.service.BrokerConfigService.Source;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The apply contract (ADR-0067 D3, D4, D7, D12): canary first, verified by a
 * read-back, halted on the first failure with the rest not attempted, hazards
 * acknowledged before a write, and the plan the operator saw being the plan that
 * runs.
 *
 * <p>The broker layer is a simulated one: {@link BrokerConfigOperations} is mocked so
 * that a write mutates an in-memory per-node state and a read returns it, which is
 * exactly what verification needs to be meaningful. A test that wants the read-back
 * to disagree with the write simply makes the write a no-op.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class BrokerConfigApplyServiceTest extends PostgresIntegrationTest {

    private static final String MATCH = "orders.#";

    @Autowired
    BrokerConfigApplyService apply;

    @Autowired
    BrokerConfigService config;

    @Autowired
    BrokerConfigDriftService drift;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    ClusterLock lock;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerConfigOperations ops;

    private UUID clusterId;
    private UUID firstId;
    private UUID secondId;
    private UUID deadId;

    /** What each simulated broker currently holds: node → match → values. */
    private final Map<UUID, Map<String, Map<String, Object>>> broker = new HashMap<>();

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        firstId = node("a-first", true);
        secondId = node("b-second", true);
        deadId = node("c-dead", false);

        // One client per node, so a write can be attributed to the node it went to.
        for (UUID id : List.of(firstId, secondId, deadId)) {
            BrokerNodeEntity n = nodes.findById(id).orElseThrow();
            JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
            when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
            when(connections.forCluster(eq(clusterId), eq(n.getJolokiaUrl()))).thenReturn(client);
            clientToNode.put(client, id);
        }

        when(ops.read(any(), any(), anyString(), any())).thenAnswer(inv -> {
            UUID nodeId = inv.getArgument(1);
            String name = inv.getArgument(2);
            return new ObservedNodeConfig(
                    nodeId,
                    name,
                    true,
                    Map.of(),
                    Map.of(),
                    broker.getOrDefault(nodeId, Map.of()),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null);
        });
        doAnswer(inv -> {
                    UUID nodeId = nodeFor(inv.getArgument(0));
                    broker.computeIfAbsent(nodeId, k -> new HashMap<>()).put(inv.getArgument(2), inv.getArgument(3));
                    return null;
                })
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());
    }

    private final Map<JolokiaBrokerClient, UUID> clientToNode = new HashMap<>();

    private UUID nodeFor(Object client) {
        return clientToNode.get(client);
    }

    private UUID node(String name, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachManagementUrl("http://" + name + ":8161/console/jolokia");
        n.applyHaState(active, "STARTED", "PRIMARY", null, 1L, "2.44.0", null, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void declare(String match, Map<String, Object> values) {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1, List.of(), List.of(new AddressSettingDecl(match, values)), List.of(), List.of());
        config.save(clusterId, doc, null, "test", Source.EDIT);
    }

    private BrokerConfigApplyRequest confirmed(BrokerConfigApplyOutcome preview) {
        return new BrokerConfigApplyRequest(
                null,
                Set.of(),
                null,
                false,
                preview.plan().highHazardIds(),
                preview.plan().planHash(),
                false);
    }

    private static NodeApply node(BrokerConfigApplyOutcome o, UUID id) {
        return o.nodes().stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    // ---- canary, verify, continue ------------------------------------------

    @Test
    void appliesToTheCanaryVerifiesItThenContinuesToTheNextLiveNode() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.plan().canaryNodeId()).isEqualTo(firstId);
        assertThat(preview.plan().stepCount()).isEqualTo(2);
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(node(outcome, firstId).canary()).isTrue();
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, firstId)).verified()).isEqualTo(Verification.VERIFIED);
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, secondId)).verified()).isEqualTo(Verification.VERIFIED);
        assertThat(node(outcome, deadId).steps()).isEmpty();
        assertThat(node(outcome, deadId).live()).isFalse();
        assertThat(broker.get(firstId)).containsKey(MATCH);
        assertThat(broker.get(secondId)).containsKey(MATCH);

        // Re-running converges: everything is ALREADY and no write is issued.
        BrokerConfigApplyOutcome again = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(again.plan().stepCount()).isZero();
    }

    @Test
    void aCanaryFailureHaltsTheRunAndTheRestIsNotAttempted() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "Error while parsing MetaData"))
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.FAILED);
        assertThat(only(node(outcome, firstId)).error()).contains("parsing");
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.NOT_ATTEMPTED);
        assertThat(outcome.summary())
                .contains("Halted")
                .contains("not attempted")
                .contains("Nothing was rolled back")
                .contains("converges");
        assertThat(broker).doesNotContainKey(secondId);
    }

    @Test
    void aReadBackThatDisagreesWithTheWriteIsAMismatchAndHalts() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        // The write "succeeds" but the broker does not reflect it.
        doAnswer(inv -> null).when(ops).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, firstId)).verified()).isEqualTo(Verification.MISMATCH);
        assertThat(node(outcome, firstId).note()).contains("does not match");
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.NOT_ATTEMPTED);
    }

    // ---- the gates ------------------------------------------------------------

    @Test
    void aHighHazardMustBeAcknowledgedBeforeAnythingIsWritten() {
        // A catch-all match is a High hazard on every node it touches.
        declare("#", Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        List<String> high = preview.plan().highHazardIds();
        assertThat(high).isNotEmpty();
        assertThat(preview.plan().hazards())
                .anyMatch(h -> h.kind() == Plan.HazardKind.BROAD_MATCH
                        && h.hazardClass().name().equals("HIGH"));

        assertThatThrownBy(() -> apply.apply(
                        clusterId,
                        new BrokerConfigApplyRequest(
                                null,
                                Set.of(),
                                null,
                                false,
                                List.of(),
                                preview.plan().planHash(),
                                false)))
                .isInstanceOf(HazardNotAcknowledgedException.class)
                .hasMessageContaining(high.get(0));
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));
        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void aPlanThatChangedSinceThePreviewIsRefused() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        assertThatThrownBy(() -> apply.apply(
                        clusterId,
                        new BrokerConfigApplyRequest(null, Set.of(), null, false, List.of(), "not-the-hash", false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("plan");
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
    }

    @Test
    void aSecondApplyWhileOneHoldsTheClusterIsRefusedNotQueued() throws Exception {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());

        // Another instance is mid-apply: it holds the cluster's apply lock on its own
        // connection until told to let go.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> apply.apply(clusterId, confirmed(preview)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("apply");
            verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
        } finally {
            release.countDown();
            other.join(10_000);
        }
    }

    // ---- the audit row ------------------------------------------------------

    @Test
    void oneAuditEventPerApplyCarriesTheNodeByStepDetail() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        apply.apply(clusterId, confirmed(preview));

        List<AuditEventEntity> events = auditEvents.findAll().stream()
                .filter(e -> clusterId.equals(e.getClusterId()))
                .filter(e -> BrokerConfigApplyService.AUDIT_APPLY.equals(e.getAction()))
                .toList();
        // The dry run is audited too, flagged as such.
        assertThat(events).hasSize(2);
        AuditEventEntity real =
                events.stream().filter(e -> !e.isDryRun()).findFirst().orElseThrow();
        assertThat(real.getTargetType()).isEqualTo("cluster");
        assertThat(real.getOutcome()).isEqualTo("SUCCESS");
        assertThat(real.getOutcomeDetail())
                .contains("a-first")
                .contains("b-second")
                .contains("APPLIED");
        assertThat(events.stream()
                        .filter(AuditEventEntity::isDryRun)
                        .findFirst()
                        .orElseThrow()
                        .getOutcome())
                .isEqualTo("SUCCESS");
    }

    // ---- drift ----------------------------------------------------------------

    @Test
    void driftReportsInSyncAfterAnApplyAndDriftedOnceANodeDiverges() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));

        BrokerConfigDriftService.Report report = drift.evaluate(clusterId);
        assertThat(state(report, firstId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, secondId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, deadId)).isEqualTo(State.NOT_EVALUATED);

        // Someone changes the second node behind Studio's back.
        broker.get(secondId).put(MATCH, Map.of("maxSizeBytes", 1L));
        report = drift.evaluate(clusterId);
        assertThat(state(report, firstId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, secondId)).isEqualTo(State.DRIFTED);
        assertThat(report.nodes().stream()
                        .filter(n -> n.nodeId().equals(secondId))
                        .findFirst()
                        .orElseThrow()
                        .findings())
                .anyMatch(f -> f.kind() == Plan.FindingKind.DIVERGENT && MATCH.equals(f.key()));
    }

    // ---- helpers ----------------------------------------------------------

    private static StepApply only(NodeApply n) {
        assertThat(n.steps()).hasSize(1);
        return n.steps().get(0);
    }

    private static State state(BrokerConfigDriftService.Report r, UUID nodeId) {
        return r.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow()
                .state();
    }
}
