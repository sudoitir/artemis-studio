package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class TopologyDiscoveryTest extends PostgresIntegrationTest {

    private static final String SEED_URL = "http://localhost:8161/console/jolokia";
    private final JsonMapper mapper = new JsonMapper();

    @Autowired
    TopologyDiscovery discovery;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    private UUID newCluster() {
        return clusters.save(new ClusterEntity("t-" + UUID.randomUUID(), null, null))
                .getId();
    }

    /** A fresh client + mock server answering, in order: search broker, HA read, listNetworkTopology. */
    private ProbedSeed seed(String topologyFixture) {
        return seed("ha-read-primary.json", topologyFixture);
    }

    private ProbedSeed seed(String haFixture, String topologyFixture) {
        return seed(SEED_URL, haFixture, topologyFixture);
    }

    private ProbedSeed seed(String url, String haFixture, String topologyFixture) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(json("search-broker.json"));
        server.expect(requestTo(url)).andRespond(json(haFixture));
        server.expect(requestTo(url)).andRespond(json(topologyFixture));
        return new ProbedSeed(url, new JolokiaBrokerClient(builder.build(), url, mapper));
    }

    /** A seed whose broker is down: the first call, resolving the broker's MBean, gets no answer. */
    private ProbedSeed deadSeed(String url) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(withException(new ConnectException("Connection refused")));
        return new ProbedSeed(url, new JolokiaBrokerClient(builder.build(), url, mapper));
    }

    private static ResponseCreator json(String fixture) {
        try {
            String body = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(body, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void discoversBothSidesOfAPairFromOneSeed() {
        UUID clusterId = newCluster();

        ClusterTopology topology = discovery.discover(clusterId, List.of(seed("topology.json")));

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        assertThat(rows)
                .extracting(BrokerNodeEntity::getName)
                .containsExactlyInAnyOrder("artemis-primary:61616", "artemis-backup:61616");

        BrokerNodeEntity primary = rows.stream()
                .filter(n -> n.getName().equals("artemis-primary:61616"))
                .findFirst()
                .orElseThrow();
        BrokerNodeEntity backup = rows.stream()
                .filter(n -> n.getName().equals("artemis-backup:61616"))
                .findFirst()
                .orElseThrow();

        // The seed's management URL is attached to the primary side.
        assertThat(primary.getJolokiaUrl()).isEqualTo(SEED_URL);
        assertThat(primary.getHaRole()).isEqualTo("PRIMARY");

        // The backup is known only by report: core connector, no management URL.
        assertThat(backup.getJolokiaUrl()).isNull();
        assertThat(backup.getCoreUrl()).isEqualTo("artemis-backup:61616");
        assertThat(backup.isDiscovered()).isTrue();

        // Both sides share the NodeID → one logical node.
        assertThat(primary.getArtemisNodeId()).isEqualTo(backup.getArtemisNodeId());
        assertThat(topology.nodes()).hasSize(1);
        assertThat(topology.nodes().get(0).endpoints()).hasSize(2);
        assertThat(topology.unmanaged()).extracting(NodeEndpoint::name).containsExactly("artemis-backup:61616");
    }

    @Test
    void aSeedWhoseHaReadFailedInsideAnOkResponseIsNotTakenForAStoppedBroker() {
        UUID clusterId = newCluster();

        assertThatThrownBy(() -> discovery.discover(
                        clusterId, List.of(seed("ha-read-instance-not-found.json", "topology.json"))))
                .isInstanceOf(BrokerConnectionException.class);

        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).isEmpty();
    }

    @Test
    void manualOverrideRowSurvivesRediscoveryUnchanged() {
        UUID clusterId = newCluster();
        discovery.discover(clusterId, List.of(seed("topology.json")));

        BrokerNodeEntity backup =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        backup.applyManualUrl("http://localhost:8261/console/jolokia");
        nodes.saveAndFlush(backup);

        discovery.discover(clusterId, List.of(seed("topology.json")));

        BrokerNodeEntity after =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        assertThat(after.getJolokiaUrl()).isEqualTo("http://localhost:8261/console/jolokia");
        assertThat(after.isManualOverride()).isTrue();
    }

    @Test
    void postFailoverTopologyWithNoBackupKeyKeepsTheBackupRow() {
        UUID clusterId = newCluster();
        discovery.discover(clusterId, List.of(seed("topology.json")));

        // The seed now reports a single-node topology (no "backup" key).
        discovery.discover(clusterId, List.of(seed("topology-after-failover.json")));

        assertThat(nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616"))
                .isPresent();
    }

    @Test
    void afterAFailoverEveryUrlStaysOnItsOwnRowAndNoRowIsAdded() {
        String primaryUrl = "http://artemis-secondary:8161/console/jolokia";
        String backupUrl = "http://artemis-secondary-backup:8161/console/jolokia";
        UUID clusterId = newCluster();
        discovery.discover(
                clusterId,
                List.of(
                        seed(primaryUrl, "ha-read-primary.json", "topology-secondary-pair.json"),
                        seed(backupUrl, "ha-read-backup.json", "topology-secondary-pair.json")));

        // The backup took over and reports itself PRIMARY; the old primary is back as a backup.
        discovery.discover(
                clusterId,
                List.of(
                        seed(backupUrl, "ha-read-primary.json", "topology-secondary-after-failover.json"),
                        seed(primaryUrl, "ha-read-backup.json", "topology-secondary-after-failover.json")));

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        assertThat(rows)
                .extracting(BrokerNodeEntity::getName, BrokerNodeEntity::getJolokiaUrl, BrokerNodeEntity::getHaRole)
                .containsExactly(
                        tuple("artemis-secondary-backup:61616", backupUrl, "PRIMARY"),
                        tuple("artemis-secondary:61616", primaryUrl, "BACKUP"));
    }

    @Test
    void aDeadSeedDoesNotStopDiscoveryFromTheSeedsThatAnswered() {
        UUID clusterId = newCluster();

        discovery.discover(
                clusterId, List.of(deadSeed("http://artemis-backup:8161/console/jolokia"), seed("topology.json")));

        BrokerNodeEntity primary =
                nodes.findByClusterIdAndName(clusterId, "artemis-primary:61616").orElseThrow();
        assertThat(primary.getJolokiaUrl()).isEqualTo(SEED_URL);
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).hasSize(2);
    }
}
