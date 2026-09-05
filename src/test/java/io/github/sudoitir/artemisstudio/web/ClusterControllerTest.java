package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.broker.BrokerClientFactory;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerCredentialRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(AdminAuthenticationExtension.class)
class ClusterControllerTest extends PostgresIntegrationTest {

    private static final String SEED = "http://broker-1:8161/console/jolokia";
    private static final String OVERRIDE_URL = "http://broker-2:8261/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    BrokerCredentialRepository credentials;

    @Autowired
    AuditEventRepository audits;

    @MockitoBean
    BrokerClientFactory clientFactory;

    @MockitoBean
    BrokerConnections connections;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        audits.deleteAll();
        clusters.deleteAll();
    }

    /** A client whose mock server answers the given fixtures at {@link #SEED}, in order. */
    private JolokiaBrokerClient client(String url, String... fixtures) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String fixture : fixtures) {
            server.expect(requestTo(url)).andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private JolokiaBrokerClient unauthorizedClient(String url) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private static String body(String fixture) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The full call sequence one client sees during register: connect, discover, capability probe. */
    private static String[] registerSequence() {
        return new String[] {
            "search-broker.json", // connectAll -> resolveBrokerObjectName
            "ha-read-primary.json", // discover -> readBrokerAttributes
            "topology.json", // discover -> listNetworkTopology
            "capability-version-read.json", // probe -> MANAGEMENT_READ
            "topology.json", // probe -> MANAGEMENT_WRITE (listNetworkTopology)
            "acceptors.json", // probe -> CORE acceptor search
            "acceptor-params-core.json", // probe -> acceptor Parameters
            "addresses-with-notifications.json", // probe -> notifications address search
            "address-settings.json" // probe -> slow-consumer detection (ADR-0044)
        };
    }

    private String registerBody() {
        return """
                { "seedUrls": ["%s"], "name": "prod-emea",
                  "credentials": { "username": "artemis", "password": "artemis" } }
                """.formatted(SEED);
    }

    @Test
    void registerPersistsClusterNodesAndAudit() throws Exception {
        when(clientFactory.forNode(any(), eq(SEED))).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("prod-emea"))
                .andExpect(jsonPath("$.topology.nodes.length()").value(1))
                .andExpect(jsonPath("$.capabilities.managementRead.status").value("AVAILABLE"));

        assertThat(clusters.count()).isEqualTo(1);
        var clusterId = clusters.findAll().get(0).getId();
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).hasSize(2);
        assertThat(credentials.findByClusterIdAndKind(clusterId, "JOLOKIA_BASIC"))
                .isPresent();

        List<AuditEventEntity> registerEvents = audits.findAll().stream()
                .filter(e -> e.getAction().equals("REGISTER_CLUSTER"))
                .toList();
        assertThat(registerEvents).singleElement().satisfies(e -> {
            assertThat(e.getOutcome()).isEqualTo("SUCCESS");
            assertThat(e.isDryRun()).isFalse();
        });
    }

    @Test
    void dryRunProbesButPersistsNoCluster() throws Exception {
        // checkConnection order: connect, then capability probe, then in-memory preview.
        when(clientFactory.forNode(any(), eq(SEED)))
                .thenReturn(client(
                        SEED,
                        "search-broker.json",
                        "capability-version-read.json",
                        "topology.json",
                        "acceptors.json",
                        "acceptor-params-core.json",
                        "addresses-with-notifications.json",
                        "address-settings.json",
                        "ha-read-primary.json",
                        "topology.json"));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachableSeeds").value(1))
                .andExpect(jsonPath("$.discoveredNodes").value(2));

        assertThat(clusters.count()).isZero();
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("SUCCESS");
            assertThat(e.isDryRun()).isTrue();
        });
    }

    @Test
    void badSeedYieldsClassifiedProblemDetailAndPersistsNothing() throws Exception {
        when(clientFactory.forNode(any(), eq(SEED))).thenReturn(unauthorizedClient(SEED));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.brokerErrorKind").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("broker-unauthorized")));

        assertThat(clusters.count()).isZero();
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("FAILURE");
        });
    }

    @Test
    void rediscoverKeepsAnOverriddenNode() throws Exception {
        when(clientFactory.forNode(any(), eq(SEED))).thenReturn(client(SEED, registerSequence()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());
        var clusterId = clusters.findAll().get(0).getId();
        var backup =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();

        // PATCH the backup with a reachable management URL.
        when(connections.forCluster(eq(clusterId), eq(OVERRIDE_URL)))
                .thenReturn(client(OVERRIDE_URL, "search-broker.json"));
        mvc.perform(patch("/api/v1/clusters/{c}/nodes/{n}", clusterId, backup.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jolokiaUrl\":\"" + OVERRIDE_URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualOverride").value(true));

        // Rediscover: every manageable node answers search + HA read + topology.
        when(connections.forCluster(eq(clusterId), any()))
                .thenAnswer(inv ->
                        client(inv.getArgument(1), "search-broker.json", "ha-read-primary.json", "topology.json"));

        mvc.perform(post("/api/v1/clusters/{c}/rediscover", clusterId)).andExpect(status().isOk());

        var after =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        assertThat(after.getJolokiaUrl()).isEqualTo(OVERRIDE_URL);
        assertThat(after.isManualOverride()).isTrue();
    }

    @Test
    void deleteRemovesClusterAndCredentialsButKeepsAudit() throws Exception {
        when(clientFactory.forNode(any(), eq(SEED))).thenReturn(client(SEED, registerSequence()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());
        var clusterId = clusters.findAll().get(0).getId();

        mvc.perform(delete("/api/v1/clusters/{c}", clusterId)).andExpect(status().isNoContent());

        assertThat(clusters.count()).isZero();
        assertThat(nodes.count()).isZero();
        assertThat(credentials.count()).isZero();
        assertThat(audits.findAll())
                .extracting(AuditEventEntity::getAction)
                .contains("REGISTER_CLUSTER", "DELETE_CLUSTER");
        assertThat(audits.findAll())
                .allSatisfy(e -> assertThat(e.getClusterId()).isNull());
    }

    @Test
    void unknownClusterIs404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/topology", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }
}
