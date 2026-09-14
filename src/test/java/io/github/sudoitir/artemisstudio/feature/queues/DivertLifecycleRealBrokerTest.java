package io.github.sudoitir.artemisstudio.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import io.github.sudoitir.artemisstudio.support.McpFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Divert creation against a real Artemis: what the broker deployed is what is reported, and
 * the diverts that would break routing are refused before anything is created.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class DivertLifecycleRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    WebApplicationContext webContext;

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

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<String> created = new ArrayList<>();
    private UUID clusterId;
    private JolokiaBrokerClient client;
    private String broker;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "divert-real-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        client = connections.forCluster(clusterId, ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
    }

    @AfterEach
    void cleanUp() {
        for (String name : created) {
            try {
                divertOps.destroyDivert(client, broker, name);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private String address(String suffix) {
        String name = "DIVERT.REAL." + run + "." + suffix;
        queueOps.createAddress(client, broker, name, "MULTICAST");
        return name;
    }

    private NodeOutcome create(String name, String from, String to, boolean dryRun) {
        created.add(name);
        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) lifecycle.createDivert(
                        clusterId, new CreateDivertRequest(name, null, from, to, false, null, null, null), dryRun))
                .value();
        assertThat(outcome.nodes()).hasSize(1);
        return outcome.nodes().get(0);
    }

    @Test
    void anIdenticalDivertIsAlreadyThereAndADifferentOneUnderTheSameNameFailsNamingTheField() {
        String a = address("A");
        String b = address("B");
        String c = address("C");
        String name = "copy-" + run;

        assertThat(create(name, a, b, false).status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(divertOps.find(client, name)).isPresent();
        assertThat(create(name, a, b, false).status()).isEqualTo(NodeStatus.ALREADY);

        NodeOutcome different = create(name, a, c, false);
        assertThat(different.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(different.error()).contains("forwarding-address");
    }

    @Test
    void aForwardingAddressThatDoesNotExistAndIsNotAutoCreatedIsRefusedInThePreviewAndForReal() {
        String match = "NOAUTO." + run + ".#";
        client.single(JolokiaRequest.exec(
                broker,
                "addAddressSettings(java.lang.String,java.lang.String)",
                match,
                "{\"autoCreateAddresses\":false}"));
        try {
            String a = address("SRC");
            String missing = "NOAUTO." + run + ".MISSING";
            String name = "to-nowhere-" + run;

            NodeOutcome preview = create(name, a, missing, true);
            assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(preview.error()).contains(missing).contains("<address name=");

            assertThat(create(name, a, missing, false).status()).isEqualTo(NodeStatus.FAILED);
            assertThat(divertOps.find(client, name)).isEmpty();
        } finally {
            client.single(JolokiaRequest.exec(broker, "removeAddressSettings(java.lang.String)", match));
        }
    }

    @Test
    void aDivertThatWouldCloseACycleIsRefusedNamingTheCycle() {
        String a = address("A");
        String b = address("B");
        assertThat(create("ab-" + run, a, b, false).status()).isEqualTo(NodeStatus.APPLIED);

        NodeOutcome back = create("ba-" + run, b, a, false);

        assertThat(back.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(back.error()).contains("cycle").contains(b + " → " + a + " → " + b);
        assertThat(divertOps.find(client, "ba-" + run)).isEmpty();
    }

    @Test
    void anInvalidRequestIsRejectedPerFieldAndACaptureNameIsRefusedOverRest() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String path = "/api/v1/clusters/" + clusterId + "/diverts";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"bad,name","address":"A","forwardingAddress":"B","routingType":"SIDEWAYS"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'routingType')]").exists());

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"artemis-studio.capture.x","address":"A","forwardingAddress":"B"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reserved")));
    }

    @Test
    void aCaptureNameIsRefusedOverMcp() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        McpFixture.Key key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(Permissions.CLUSTER_READ, QueuePermissions.DIVERT_WRITE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of(
                        "clusterId", clusterId.toString(),
                        "kind", "create_divert",
                        "name", "artemis-studio.capture.x",
                        "config", "{\"address\":\"A\",\"forwardingAddress\":\"B\"}",
                        "dryRun", false));

        assertThat(response.toString()).contains("reserved for message capture");
    }
}
