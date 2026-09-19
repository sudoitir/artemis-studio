package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.feature.queues.DivertOperations;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The apply the Configuration screen sends, against a real Artemis: every section of a
 * declaration reaches the broker, and applying it again writes nothing.
 *
 * <p>The real run posts without {@code dryRun}, the way the screen's Apply did before the
 * query helper spelled out {@code dryRun=false}. The endpoint used to default that to a
 * preview, so the operator's Apply wrote nothing and reported nothing.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class BrokerConfigApplyRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    BrokerConfigService configs;

    @Autowired
    BrokerConfigOperations ops;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    ObjectMapper mapper;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String orders = "C1.REAL." + run + ".ORDERS";
    private final String audit = "C1.REAL." + run + ".AUDIT";
    private final String auditQueue = "C1.REAL." + run + ".AUDIT.KEEP";
    private final String match = "C1.REAL." + run + ".#";
    private final String divert = "c1-real-" + run;

    private MockMvc mvc;
    private UUID clusterId;
    private JolokiaBrokerClient client;
    private String broker;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "config-real-" + run,
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
        quietly(() -> divertOps.destroyDivert(client, broker, divert));
        quietly(() -> queueOps.destroyQueue(client, broker, orders));
        quietly(() -> queueOps.deleteAddress(client, broker, orders));
        quietly(() -> queueOps.destroyQueue(client, broker, auditQueue));
        quietly(() -> queueOps.deleteAddress(client, broker, audit));
        quietly(() -> ops.removeAddressSettings(client, broker, match));
        quietly(() -> ops.removeSecuritySettings(client, broker, match));
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    @Test
    void anApplyWithoutDryRunWritesEverySectionAndASecondApplyFindsNothingToDo() throws Exception {
        configs.save(clusterId, document(), null, "c1", BrokerConfigService.Source.EDIT);

        JsonNode preview = apply("?dryRun=true", "{}");
        assertThat(preview.path("outcome").asString()).isEqualTo("DRY_RUN");
        List<String> acknowledge = StreamSupport.stream(
                        preview.path("plan").path("hazards").spliterator(), false)
                .filter(h -> "HIGH".equals(h.path("hazardClass").asString()))
                .map(h -> h.path("id").asString())
                .toList();

        JsonNode applied = apply(
                "",
                mapper.writeValueAsString(Map.of(
                        "expectedPlanHash",
                        preview.path("plan").path("planHash").asString(),
                        "acknowledgedHazards",
                        acknowledge)));

        assertThat(applied.path("dryRun").asBoolean()).isFalse();
        assertThat(applied.path("outcome").asString()).isEqualTo("APPLIED");

        Set<String> addresses = names("AddressNames");
        assertThat(addresses).contains(orders, audit);
        assertThat(names("QueueNames")).contains(orders, auditQueue);
        assertThat(divertOps.find(client, divert)).isPresent();
        assertThat(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, match).asString())
                .contains("\"maxDeliveryAttempts\":7");
        assertThat(exec(BrokerConfigOperations.GET_ROLES, match).asString()).contains("c1-role");

        JsonNode again = apply("", "{}");
        assertThat(again.path("dryRun").asBoolean()).isFalse();
        assertThat(again.path("plan").path("stepCount").asInt()).isZero();
        assertThat(again.path("nodes")
                        .valueStream()
                        .flatMap(n -> n.path("steps").valueStream()))
                .allMatch(s -> "ALREADY".equals(s.path("status").asString()));
    }

    private BrokerConfigDocument document() {
        return new BrokerConfigDocument(
                BrokerConfigDocument.CURRENT_VERSION,
                List.of(
                        new AddressDecl(
                                orders,
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl(orders, "ANYCAST", null, true, null, null, null, null, null))),
                        new AddressDecl(
                                audit,
                                Set.of("MULTICAST"),
                                List.of(new QueueDecl(
                                        auditQueue, "MULTICAST", null, true, null, null, null, null, null)))),
                List.of(new AddressSettingDecl(match, Map.of("maxDeliveryAttempts", 7))),
                List.of(new SecuritySettingDecl(
                        match, Map.of(PermissionType.SEND, Set.of("c1-role"), PermissionType.CONSUME, Set.of("amq")))),
                List.of(new DivertDecl(divert, orders, audit, null, false, null, null, null)));
    }

    private JsonNode apply(String query, String body) throws Exception {
        var response = mvc.perform(post("/api/v1/clusters/" + clusterId + "/config/apply" + query)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        String json = response.getContentAsString();
        assertThat(response.getStatus()).describedAs(json).isEqualTo(200);
        return mapper.readTree(json);
    }

    private Set<String> names(String attribute) {
        JsonNode value = client.single(JolokiaRequest.read(broker, attribute)).attribute(attribute);
        return Set.copyOf(value.valueStream().map(JsonNode::asString).toList());
    }

    private JsonNode exec(String operation, String argument) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(broker, operation, argument));
        assertThat(res.ok()).describedAs("%s: %s", operation, res.error()).isTrue();
        return res.value();
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // already gone, or never created
        }
    }
}
