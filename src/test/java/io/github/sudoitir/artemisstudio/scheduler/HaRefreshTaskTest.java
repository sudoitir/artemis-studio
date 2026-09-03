package io.github.sudoitir.artemisstudio.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.domain.topology.HaStateEvaluator;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class HaRefreshTaskTest {

    private static final String GOOD_URL = "http://good:8161/console/jolokia";
    private static final String BAD_URL = "http://bad:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    ClusterRepository clusters;

    @Mock
    BrokerNodeRepository nodes;

    @Mock
    BrokerConnections connections;

    HaRefreshTask task;

    @BeforeEach
    void setUp() {
        task = new HaRefreshTask(clusters, nodes, connections, new HaStateEvaluator());
    }

    private JolokiaBrokerClient okClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GOOD_URL)).andRespond(body("search-broker.json"));
        server.expect(requestTo(GOOD_URL)).andRespond(body("ha-read-primary.json"));
        return new JolokiaBrokerClient(builder.build(), GOOD_URL, mapper);
    }

    private static org.springframework.test.web.client.ResponseCreator body(String fixture) {
        try {
            String json = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(json, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void oneFailingNodeIsRecordedAndDoesNotAbortTheOthers() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = new ClusterEntity("c", null, null);
        // id is generated on persist; stub findById-style access via a spy-free approach:
        BrokerNodeEntity good = BrokerNodeEntity.fromSeed(clusterId, "good", "PRIMARY", null);
        good.attachManagementUrl(GOOD_URL);
        BrokerNodeEntity bad = BrokerNodeEntity.fromSeed(clusterId, "bad", "PRIMARY", null);
        bad.attachManagementUrl(BAD_URL);

        when(clusters.findAll()).thenReturn(List.of(cluster));
        when(nodes.findByClusterIdOrderByNameAsc(cluster.getId())).thenReturn(List.of(bad, good));
        when(connections.forCluster(eq(cluster.getId()), eq(BAD_URL)))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(eq(cluster.getId()), eq(GOOD_URL))).thenReturn(okClient());

        task.refresh();

        assertThat(bad.getLastError()).isNotNull();
        assertThat(bad.getObservedCycle()).isNull();

        assertThat(good.getLastError()).isNull();
        assertThat(good.getObservedCycle()).isEqualTo(1L);
        assertThat(good.getState()).isEqualTo("STARTED");
        assertThat(good.getActive()).isTrue();
        assertThat(good.getHaRole()).isEqualTo("PRIMARY");
    }

    @Test
    void cycleNumberIncrementsPerRun() {
        when(clusters.findAll()).thenReturn(List.of());

        task.refresh();
        task.refresh();

        // No nodes touched, but the counter advanced — a manageable node next run
        // would be tagged cycle 3.
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = new ClusterEntity("c", null, null);
        BrokerNodeEntity good = BrokerNodeEntity.fromSeed(clusterId, "good", "PRIMARY", null);
        good.attachManagementUrl(GOOD_URL);
        when(clusters.findAll()).thenReturn(List.of(cluster));
        when(nodes.findByClusterIdOrderByNameAsc(cluster.getId())).thenReturn(List.of(good));
        when(connections.forCluster(eq(cluster.getId()), eq(GOOD_URL))).thenReturn(okClient());

        task.refresh();

        assertThat(good.getObservedCycle()).isEqualTo(3L);
    }
}
