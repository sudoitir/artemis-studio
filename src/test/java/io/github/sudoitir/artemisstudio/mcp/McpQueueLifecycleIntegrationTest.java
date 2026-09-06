package io.github.sudoitir.artemisstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The {@code queue_lifecycle} tool's contract from the model's side of the wire
 * (ADR-0049): preview by default, a matching {@code confirm} before anything is
 * destroyed, the caller's own permissions, and the full per-node outcome.
 */
class McpQueueLifecycleIntegrationTest extends PostgresIntegrationTest {

    private static final String QUEUE = "MCP.LIFECYCLE";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    QueueLifecycleOperations ops;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity("lifecycle-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachManagementUrl("http://a:8161/console/jolokia");
        node.applyHaState(true, "STARTED", "PRIMARY", null, 1L, "2.44.0", null, Instant.now());
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(
                List.of(new QueueRow(clusterId, nodeId, QUEUE, QUEUE, "ANYCAST", true, 9, 0, 0, 0, 0, 0, 0, false)));

        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(connections.forCluster(eq(clusterId), anyString())).thenReturn(client);
        when(ops.messageCount(any(), anyString())).thenReturn(9L);
    }

    @AfterEach
    void cleanUp() {
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private McpFixture.Key keyWith(Set<String> permissions) {
        return McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.CLUSTER, clusterId, permissions);
    }

    @Test
    void theDefaultInvocationPreviewsAndDestroysNothing() throws Exception {
        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, Permissions.QUEUE_DELETE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "delete_queue", "name", QUEUE));

        JsonNode outcome = response.path("result").path("structuredContent");
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(outcome.path("dryRun").asBoolean()).isTrue();
        assertThat(outcome.path("totalAffected").asLong()).isEqualTo(9L);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString());
    }

    @Test
    void aRealDestroyWithoutAMatchingConfirmationIsRefused() throws Exception {
        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, Permissions.QUEUE_DELETE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "kind",
                        "delete_queue",
                        "name",
                        QUEUE,
                        "dryRun",
                        false,
                        "confirm",
                        "some-other-queue"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains(QUEUE);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString());
    }

    @Test
    void theToolInheritsTheCallersPermissions() throws Exception {
        // Every read permission, but no destroy.
        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, Permissions.MESSAGE_READ));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "delete_queue", "name", QUEUE));

        // Refused on the same basis as the HTTP request: a not-found that reveals
        // nothing about whether the cluster exists.
        assertThat(response.path("result").path("isError").asBoolean(false)
                        || response.path("error").isObject())
                .describedAs("%s", response)
                .isTrue();
        verify(ops, never()).destroyQueue(any(), anyString(), anyString());
    }

    @Test
    void aPartialApplicationIsReturnedPerNode() throws Exception {
        // A second node that is not live, so the fan-out is uneven by construction.
        BrokerNodeEntity dead = BrokerNodeEntity.fromSeed(
                clusterId, "node-b", "PRIMARY", UUID.randomUUID().toString());
        dead.attachManagementUrl("http://b:8161/console/jolokia");
        nodes.save(dead);

        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, Permissions.QUEUE_PAUSE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "pause_queue", "name", QUEUE, "dryRun", false));

        JsonNode nodesOut = response.path("result").path("structuredContent").path("nodes");
        assertThat(nodesOut).describedAs("%s", response).hasSize(2);
        assertThat(nodesOut.valueStream().map(n -> n.path("status").asString()).toList())
                .contains("SKIPPED_NOT_LIVE");
    }
}
