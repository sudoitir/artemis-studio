package io.github.sudoitir.artemisstudio.feature.brokerconfig.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
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
 * {@code GET .../config-diff} — every node against the majority, the expected-vs-drift
 * split, and the cases where Studio must state a limitation rather than render a diff
 * (ADR-0043, ADR-0178).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ConfigDiffControllerTest extends PostgresIntegrationTest {

    private static final String A_URL = "http://a:8161/console/jolokia";
    private static final String B_URL = "http://b:8261/console/jolokia";
    private static final String C_URL = "http://c:8361/console/jolokia";
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
    private UUID aId;
    private UUID bId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        aId = node("node-a", "PRIMARY", A_URL);
        bId = node("node-b", "BACKUP", B_URL);
        node("node-c", "STANDALONE", C_URL);
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

    private static org.springframework.test.web.servlet.ResultMatcher nodeIs(String name, String field, Object value) {
        return jsonPath("$.nodes[?(@.nodeName == '" + name + "')]." + field)
                .value(org.hamcrest.Matchers.hasItem(value));
    }

    private static String broker(String path) {
        return "$.sections[?(@.section == 'broker')].keys[?(@.key == '" + path + "')]";
    }

    private void threeNodes(String cType) {
        when(connections.forCluster(eq(clusterId), eq(A_URL))).thenReturn(client(A_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(B_URL))).thenReturn(client(B_URL, "backup", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(C_URL))).thenReturn(client(C_URL, "third", cType, true));
    }

    @Test
    void comparesEveryNodeAndNamesTheOutlierAgainstTheMajority() throws Exception {
        threeNodes("NIO");

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(
                        jsonPath(broker("/JournalType") + ".majority").value(org.hamcrest.Matchers.hasItem("ASYNCIO")))
                .andExpect(jsonPath(broker("/JournalType") + ".outliers[0].nodeName")
                        .value(org.hamcrest.Matchers.hasItem("node-c")))
                .andExpect(jsonPath(broker("/JournalType") + ".outliers[0].value")
                        .value(org.hamcrest.Matchers.hasItem("NIO")))
                .andExpect(jsonPath(broker("/JournalType") + ".drift").value(org.hamcrest.Matchers.hasItem(true)))
                // Name differs by design, and a runtime counter is not configuration.
                .andExpect(
                        jsonPath(broker("/Name") + ".classification").value(org.hamcrest.Matchers.hasItem("EXPECTED")))
                .andExpect(jsonPath(broker("/TotalMessageCount") + ".classification")
                        .value(org.hamcrest.Matchers.hasItem("UNCLASSIFIED")))
                // The acceptor host is expected too, so JournalType is the only drift, on one node.
                .andExpect(jsonPath("$.summary.driftKeys").value(1))
                .andExpect(jsonPath("$.summary.driftNodes").value(1))
                .andExpect(jsonPath("$.summary.expectedKeys").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void stateIsCarriedAsAWordNotOnlyAsAnEnum() throws Exception {
        threeNodes("NIO");

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath(broker("/JournalType") + ".stateWord")
                        .value(org.hamcrest.Matchers.hasItem("different")));
    }

    @Test
    void anUnreadableNodeIsListedWithItsReasonAndTheOthersAreStillCompared() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(A_URL))).thenReturn(client(A_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(B_URL))).thenReturn(client(B_URL, "backup", "NIO", true));
        when(connections.forCluster(eq(clusterId), eq(C_URL))).thenReturn(unauthorized(C_URL));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(nodeIs("node-c", "available", false))
                .andExpect(nodeIs("node-c", "unavailableKind", "UNAUTHORIZED"))
                .andExpect(jsonPath("$.nodes[?(@.nodeName == 'node-c')].unavailableReason")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString()))))
                // Two answering nodes with two values have no majority; the dead node is no tiebreaker.
                .andExpect(jsonPath(broker("/JournalType") + ".valueGroups.length()")
                        .value(org.hamcrest.Matchers.hasItem(2)));
    }

    @Test
    void whenFewerThanTwoNodesAnswerNoComparisonIsMadeAndEveryReasonIsGiven() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(A_URL))).thenReturn(client(A_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(B_URL))).thenReturn(unauthorized(B_URL));
        when(connections.forCluster(eq(clusterId), eq(C_URL))).thenReturn(unauthorized(C_URL));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(false))
                .andExpect(nodeIs("node-b", "unavailableKind", "UNAUTHORIZED"))
                .andExpect(nodeIs("node-c", "unavailableKind", "UNAUTHORIZED"))
                // Never a half-diff: the unreachable nodes' absent keys would read as removals.
                .andExpect(jsonPath("$.sections.length()").value(0))
                .andExpect(jsonPath("$.summary.driftKeys").value(0));
    }

    @Test
    void nodesNarrowsTheComparisonToTheNamedNodes() throws Exception {
        when(connections.forCluster(eq(clusterId), eq(A_URL))).thenReturn(client(A_URL, "primary", "ASYNCIO", true));
        when(connections.forCluster(eq(clusterId), eq(B_URL))).thenReturn(client(B_URL, "backup", "NIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId).param("nodes", aId + "," + bId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2));
    }

    @Test
    void namingFewerThanTwoNodesIsRefused() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId).param("nodes", aId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPassiveBackupThatAnswersFullyIsStillCompared() throws Exception {
        // A passive backup reports AddressNames: [] where the primary reports entries.
        // That is a value difference, not a smaller surface, and must not suppress the
        // comparison — the live check against the dev pair caught exactly this.
        when(connections.forCluster(eq(clusterId), eq(A_URL)))
                .thenReturn(client(A_URL, "primary", "ASYNCIO", true, "[\"orders\",\"events\"]"));
        when(connections.forCluster(eq(clusterId), eq(B_URL)))
                .thenReturn(client(B_URL, "backup", "ASYNCIO", false, "[]"));
        when(connections.forCluster(eq(clusterId), eq(C_URL))).thenReturn(client(C_URL, "third", "ASYNCIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(nodeIs("node-b", "reducedSurface", false))
                .andExpect(jsonPath("$.notes.length()").value(0));
    }

    @Test
    void aReadOnlyComparisonWritesNoAuditEvent() throws Exception {
        long before = auditEvents.count();
        threeNodes("ASYNCIO");

        mvc.perform(get("/api/v1/clusters/{c}/config-diff", clusterId)).andExpect(status().isOk());

        Assertions.assertThat(auditEvents.count()).isEqualTo(before);
    }
}
