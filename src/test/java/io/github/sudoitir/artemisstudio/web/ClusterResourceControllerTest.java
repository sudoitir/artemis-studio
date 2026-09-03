package io.github.sudoitir.artemisstudio.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** {@code /api/v1/clusters/{id}/{queues,consumers,...}} — one endpoint per resource, plus the capability-gated path. */
class ClusterResourceControllerTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";
    private static final String URL_B = "http://b:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @MockitoBean
    BrokerConnections connections;

    private UUID clusterId;
    private UUID nodeAId;
    private UUID nodeBId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachManagementUrl(URL_A);
        BrokerNodeEntity b = BrokerNodeEntity.fromSeed(
                clusterId, "node-b", "PRIMARY", UUID.randomUUID().toString());
        b.attachManagementUrl(URL_B);
        nodeAId = nodes.save(a).getId();
        nodeBId = nodes.save(b).getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private JolokiaBrokerClient client(String url, String... fixtures) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String fixture : fixtures) {
            server.expect(requestTo(url)).andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private JolokiaBrokerClient unauthorized(String url) {
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

    private QueueRow row(UUID node, String queue, long messageCount) {
        return new QueueRow(clusterId, node, queue, queue, "ANYCAST", true, messageCount, 1, 0, 0, 0, 0, 0);
    }

    @Test
    void queuesAggregatesTheSnapshotCacheAcrossNodes() throws Exception {
        upsert.upsertBatch(List.of(row(nodeAId, "ORDERS", 10), row(nodeBId, "ORDERS", 5)));

        mvc.perform(get("/api/v1/clusters/{id}/queues", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].queueName").value("ORDERS"))
                .andExpect(jsonPath("$.data[0].totalMessageCount").value(15))
                .andExpect(jsonPath("$.data[0].nodesPresent").value(2));
    }

    @Test
    void consumersFansOutLiveAndTagsEachRowWithItsNode() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(URL_A)))
                .thenReturn(client(URL_A, "search-broker.json", "list-consumers.json"));
        when(connections.forCluster(eq(clusterId), eq(URL_B)))
                .thenReturn(client(URL_B, "search-broker.json", "list-consumers.json"));

        mvc.perform(get("/api/v1/clusters/{id}/consumers", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.data[0].nodeName").value(org.hamcrest.Matchers.startsWith("node-")))
                .andExpect(jsonPath("$.data[0].queueName").value("SPIKE.A.q000"));
    }

    @Test
    void aClusterWithNoReadableNodeSurfacesAClassifiedProblem() throws Exception {
        when(connections.forCluster(eq(clusterId), any()))
                .thenReturn(unauthorized(URL_A))
                .thenReturn(unauthorized(URL_B));

        mvc.perform(get("/api/v1/clusters/{id}/sessions", clusterId))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.brokerErrorKind").value("UNAUTHORIZED"));
    }
}
