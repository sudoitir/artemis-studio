package io.github.sudoitir.artemisstudio.web;

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
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET .../config-diff} — the pair default, the expected-vs-drift split, and
 * the two cases where Studio must state a limitation rather than render a diff
 * (ADR-0043).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ConfigDiffControllerTest extends PostgresIntegrationTest {

    private static final String LEFT_URL = "http://a:8161/console/jolokia";
    private static final String RIGHT_URL = "http://b:8261/console/jolokia";
    private static final String NODE_ID = "shared-node-id";

    private final JsonMapper mapper = new JsonMapper();

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoBean
    BrokerConnections connections;

    private UUID clusterId;
    private UUID leftId;
    private UUID rightId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        leftId = node("node-a", "PRIMARY", LEFT_URL);
        rightId = node("node-b", "BACKUP", RIGHT_URL);
    }

    private UUID node(String name, String role, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(clusterId, name, role, NODE_ID);
        n.attachManagementUrl(url);
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /**
     * One batched POST per node: a search to resolve the MBean name, then the batch.
     * The mock server fails the test if more calls are made than bodies given.
     */
    private JolokiaBrokerClient client(String url, String brokerName, String journalType, boolean active) {
        return client(url, brokerName, journalType, active, "[]");
    }

    private JolokiaBrokerClient client(
            String url, String brokerName, String journalType, boolean active, String addressNames) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(withSuccess(search(brokerName), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(
                        withSuccess(batch(brokerName, journalType, active, addressNames), MediaType.APPLICATION_JSON));
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private JolokiaBrokerClient unauthorized(String url) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private static String search(String brokerName) {
        return "{\"value\":[\"org.apache.activemq.artemis:broker=\\\"" + brokerName + "\\\"\"],\"status\":200}";
    }

    /** The four batched entries: head read, full read, roles, address settings for "#". */
    private static String batch(String brokerName, String journalType, boolean active, String addressNames) {
        String acceptors = "[{\\\"name\\\":\\\"artemis\\\",\\\"params\\\":{\\\"host\\\":\\\"" + brokerName
                + "\\\",\\\"port\\\":\\\"61616\\\",\\\"protocols\\\":\\\"CORE\\\"}}]";
        return """
                [
                  {"status":200,"value":{"Active":%s,"AcceptorsAsJSON":"%s"}},
                  {"status":200,"value":{"Name":"%s","JournalType":"%s","TotalMessageCount":%s,"AddressNames":%s}},
                  {"status":200,"value":"[{\\"name\\":\\"amq\\",\\"consume\\":true}]"},
                  {"status":200,"value":"{\\"maxSizeBytes\\":-1}"}
                ]
                """.formatted(active, acceptors, brokerName, journalType, active ? "7" : "0", addressNames);
    }

    @Test
    void comparesThePairByDefaultAndSeparatesExpectedFromDrift() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(LEFT_URL)))
                .thenReturn(client(LEFT_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(RIGHT_URL))).thenReturn(client(RIGHT_URL, "backup", "NIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId).param("right", rightId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.left.nodeId").value(leftId.toString()))
                .andExpect(jsonPath("$.right.nodeId").value(rightId.toString()))
                // Name differs by design; JournalType is real drift.
                .andExpect(jsonPath("$.sections[?(@.section == 'broker')].entries[?(@.key == '/Name')].classification")
                        .value(org.hamcrest.Matchers.hasItem("EXPECTED")))
                .andExpect(jsonPath("$.sections[?(@.section == 'broker')].entries[?(@.key == '/JournalType')].drift")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                // A runtime counter differs, and is not counted as drift.
                .andExpect(jsonPath(
                                "$.sections[?(@.section == 'broker')].entries[?(@.key == '/TotalMessageCount')].classification")
                        .value(org.hamcrest.Matchers.hasItem("UNCLASSIFIED")))
                // The acceptor's host is EXPECTED, not drift, so JournalType is the only one.
                .andExpect(jsonPath("$.driftCount").value(1));
    }

    @Test
    void statusIsCarriedAsAWordNotOnlyAsAnEnum() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(LEFT_URL)))
                .thenReturn(client(LEFT_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(RIGHT_URL))).thenReturn(client(RIGHT_URL, "backup", "NIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId)
                        .param("left", leftId.toString())
                        .param("right", rightId.toString()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.sections[?(@.section == 'broker')].entries[?(@.key == '/JournalType')].statusWord")
                                .value(org.hamcrest.Matchers.hasItem("different")));
    }

    @Test
    void whenOneSideCannotBeReadNoDiffIsRenderedAtAll() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(LEFT_URL)))
                .thenReturn(client(LEFT_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(RIGHT_URL))).thenReturn(unauthorized(RIGHT_URL));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId)
                        .param("left", leftId.toString())
                        .param("right", rightId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(false))
                .andExpect(jsonPath("$.right.available").value(false))
                .andExpect(jsonPath("$.right.unavailableReason").isNotEmpty())
                // Never a half-diff: the unreachable side's absent keys would read as removals.
                .andExpect(jsonPath("$.sections.length()").value(0))
                .andExpect(jsonPath("$.driftCount").value(0));
    }

    @Test
    void aPassiveBackupThatAnswersFullyIsStillCompared() {
        // A passive backup reports AddressNames: [] where the primary reports entries.
        // That is a value difference, not a smaller surface, and must not suppress the
        // comparison — the live check against the dev pair caught exactly this.
        when(connections.forCluster(eq(clusterId), eq(LEFT_URL)))
                .thenReturn(client(LEFT_URL, "primary", "ASYNCIO", true, "[\"orders\",\"events\"]"));
        when(connections.forCluster(eq(clusterId), eq(RIGHT_URL)))
                .thenReturn(client(RIGHT_URL, "backup", "ASYNCIO", false, "[]"));

        try {
            mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId)
                            .param("left", leftId.toString())
                            .param("right", rightId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.comparable").value(true))
                    .andExpect(jsonPath("$.right.reducedSurface").value(false));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aReadOnlyComparisonWritesNoAuditEvent() throws Exception {
        long before = auditEvents.count();
        when(connections.forCluster(eq(clusterId), eq(LEFT_URL)))
                .thenReturn(client(LEFT_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(RIGHT_URL)))
                .thenReturn(client(RIGHT_URL, "backup", "ASYNCIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId)
                        .param("left", leftId.toString())
                        .param("right", rightId.toString()))
                .andExpect(status().isOk());

        Assertions.assertThat(auditEvents.count()).isEqualTo(before);
    }
}
