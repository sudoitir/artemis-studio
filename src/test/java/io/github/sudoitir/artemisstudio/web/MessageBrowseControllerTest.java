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

/** {@code GET .../queues/{q}/messages} — page shape, single-message detail + 404, node echo, bad filter, capability path. */
class MessageBrowseControllerTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";

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

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachManagementUrl(URL_A);
        nodeAId = nodes.save(a).getId();
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeAId, "PHASE3.SRC", "PHASE3.SRC", "ANYCAST", true, 4, 0, 0, 0, 0, 0, 0)));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /** search resolves the broker MBean, then one batched browse POST. */
    private JolokiaBrokerClient client(String batchBody) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL_A))
                .andRespond(withSuccess(fixture("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL_A)).andRespond(withSuccess(batchBody, MediaType.APPLICATION_JSON));
        return new JolokiaBrokerClient(builder.build(), URL_A, mapper);
    }

    private JolokiaBrokerClient unauthorized() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL_A)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return new JolokiaBrokerClient(builder.build(), URL_A, mapper);
    }

    private static String batch(String browseFixture, long count) {
        return "[" + fixture(browseFixture) + ",{\"value\":" + count + ",\"status\":200}]";
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("jolokia/" + name).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void browseReturnsAPageAndEchoesTheNode() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(URL_A))).thenReturn(client(batch("browse.json", 4)));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].messageId").value(127))
                .andExpect(jsonPath("$.data[3].bodyTruncated").value(true))
                .andExpect(jsonPath("$.node").value(nodeAId.toString()));
    }

    @Test
    void detailFindsOneMessageById() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(URL_A))).thenReturn(client(batch("browse.json", 4)));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages/{id}", clusterId, "PHASE3.SRC", 133))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(133))
                .andExpect(jsonPath("$.stringProperties.orderId").value("A-2"))
                .andExpect(jsonPath("$.node").value(nodeAId.toString()));
    }

    @Test
    void detailIsA404WhenTheIdIsNotInRange() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(URL_A))).thenReturn(client(batch("browse.json", 4)));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages/{id}", clusterId, "PHASE3.SRC", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidFilterIsA400() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(URL_A))).thenReturn(client(batch("browse-bad-filter.json", 0)));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC")
                        .param("filter", "this is not a filter =="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid value"));
    }

    @Test
    void aBrokerThatRefusesTheReadSurfacesAClassifiedProblem() throws Exception {
        when(connections.forCluster(eq(clusterId), any())).thenReturn(unauthorized());

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.brokerErrorKind").value("UNAUTHORIZED"));
    }

    @Test
    void anUnknownQueueIsA404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "NOPE"))
                .andExpect(status().isNotFound());
    }
}
