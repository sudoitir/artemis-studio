package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.routing.DivertOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertRow;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import io.github.sudoitir.artemisstudio.web.dto.RoutingViews.DivertView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What the routing view claims, and what it refuses to claim.
 *
 * <p>The broker layer is mocked: what is under test is the merge across nodes, the
 * ownership attribution drawn from Studio's own audit trail, and the absence of any
 * origin claim (ADR-0065 D2) — none of which is Jolokia's business.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RoutingServiceTest extends PostgresIntegrationTest {

    private static final String NODE_A_URL = "http://a:8161/console/jolokia";
    private static final String NODE_B_URL = "http://b:8161/console/jolokia";

    @Autowired
    RoutingService routing;

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    DivertOperations divertOps;

    private UUID clusterId;
    private UUID nodeA;
    private UUID nodeB;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeA = node("node-a", NODE_A_URL);
        nodeB = node("node-b", NODE_B_URL);

        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(connections.forCluster(eq(clusterId), anyString())).thenReturn(client);
    }

    private UUID node(String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachManagementUrl(url);
        n.applyHaState(true, "STARTED", "PRIMARY", null, 1L, "2.56.0", null, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private static DivertRow divert(UUID nodeId, String nodeName, String name, String from, String to) {
        return new DivertRow(nodeId, nodeName, name, name, from, to, null, "STRIP", null, false, false);
    }

    private PagedView<DivertView> diverts() {
        return routing.diverts(clusterId, ResourceQuery.of(null, null, null, null));
    }

    @Test
    void oneDivertOnEveryNodeIsOneRowAttributedToBoth() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "audit", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "audit", "ORDER.IN", "AUDIT.IN")));

        List<DivertView> rows = diverts().data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).nodesPresent()).isEqualTo(2);
        assertThat(rows.get(0).nodesTotal()).isEqualTo(2);
        assertThat(rows.get(0).perNode()).extracting(n -> n.nodeName()).containsExactly("node-a", "node-b");
    }

    /**
     * The same name pointing somewhere else on one node is a divergence, and the
     * whole reason an operator opens this view. Collapsing it on the name alone
     * would report one of the two shapes and hide the other.
     */
    @Test
    void theSameNameRoutingDifferentlyOnTwoNodesIsTwoRows() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "audit", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "audit", "ORDER.IN", "ELSEWHERE.IN")));

        List<DivertView> rows = diverts().data();

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.nodesPresent() == 1 && r.nodesTotal() == 2);
        assertThat(rows)
                .extracting(DivertView::forwardingAddress)
                .containsExactlyInAnyOrder("AUDIT.IN", "ELSEWHERE.IN");
    }

    /**
     * The broker records nothing about where a divert came from, so neither does
     * Studio. A divert Studio has no record of is reported without an owner — which
     * is not the same as reporting that it came from broker configuration.
     */
    @Test
    void aDivertStudioDidNotCreateCarriesNoOriginClaim() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "someone-elses", "A", "B")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    @Test
    void aDivertStudioCreatedIsAttributedToStudioFromItsOwnAuditTrail() {
        lifecycle.createDivert(
                clusterId, new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null), false);

        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "mine", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "mine", "ORDER.IN", "AUDIT.IN")));

        assertThat(diverts().data().get(0).owner()).isEqualTo("OPERATOR");
    }

    @Test
    void aDivertStudioCreatedAndThenDeletedIsNoLongerAttributedToStudio() {
        CreateDivertRequest request = new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null);
        lifecycle.createDivert(clusterId, request, false);
        lifecycle.deleteDivert(clusterId, "mine", false);

        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "mine", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    /** A capture tap is Studio's by construction, from the namespace it reserves. */
    @Test
    void aCaptureDivertIsAttributedToCaptureAndNamesItsSubscription() {
        UUID subscription = UUID.randomUUID();
        String name = RoutingService.CAPTURE_DIVERT_PREFIX + "instance." + subscription;
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", name, "ORDER.IN", "cap")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        DivertView row = diverts().data().get(0);
        assertThat(row.owner()).isEqualTo("MESSAGE_CAPTURE");
        assertThat(row.captureSubscriptionId()).isEqualTo(subscription);
    }

    /** "What touches this address" has to match either end, not only the name. */
    @Test
    void filteringMatchesEitherEndOfTheDivert() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(
                        divert(nodeA, "node-a", "one", "ORDER.IN", "AUDIT.IN"),
                        divert(nodeA, "node-a", "two", "PAYMENT.IN", "ORDER.IN"),
                        divert(nodeA, "node-a", "three", "PAYMENT.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        List<DivertView> rows = routing.diverts(clusterId, ResourceQuery.of("ORDER.IN", null, null, null))
                .data();

        assertThat(rows).extracting(DivertView::name).containsExactlyInAnyOrder("one", "two");
    }
}
