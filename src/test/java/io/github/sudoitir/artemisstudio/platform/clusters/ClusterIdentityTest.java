package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.platform.clusters.ClusterIdentity.Overlap;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** What makes two registrations the same cluster (ADR-0167). */
class ClusterIdentityTest {

    private static final String PAIR = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";
    private static final String OTHER = "0a1b2c3d-0000-11f1-aa4c-ceae3fa2df1d";

    private final UUID prod = UUID.randomUUID();
    private final UUID staging = UUID.randomUUID();
    private final Map<UUID, String> names = Map.of(prod, "prod-emea", staging, "staging");

    private static BrokerNodeEntity node(UUID cluster, String name, String nodeId, String jolokiaUrl) {
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(cluster, name, "PRIMARY", nodeId);
        if (jolokiaUrl != null) {
            node.attachManagementUrl(jolokiaUrl);
        }
        return node;
    }

    private static ClusterIdentity identity(Set<String> nodeIds, String... seeds) {
        Set<String> urls = Arrays.stream(seeds).map(ClusterIdentity::normalise).collect(Collectors.toSet());
        return new ClusterIdentity(nodeIds, urls, nodeIds.isEmpty() ? urls : Set.of());
    }

    @Test
    void theNormalFormIgnoresCaseTrailingSlashesAndTheDefaultPort() {
        assertThat(ClusterIdentity.normalise(" HTTP://Broker-1:8161/console/jolokia/ "))
                .isEqualTo("http://broker-1:8161/console/jolokia");
        assertThat(ClusterIdentity.normalise("http://broker-1/console/jolokia"))
                .isEqualTo(ClusterIdentity.normalise("http://broker-1:80/console/jolokia"));
        assertThat(ClusterIdentity.normalise("https://broker-1/console/jolokia"))
                .isEqualTo("https://broker-1:443/console/jolokia");
        assertThat(ClusterIdentity.normalise("http://user@broker-1:8161/console/jolokia?x=1#y"))
                .isEqualTo("http://broker-1:8161/console/jolokia");
    }

    @Test
    void theNormalFormKeepsWhatNamesADifferentEndpoint() {
        assertThat(ClusterIdentity.normalise("http://broker-1:8161/console/jolokia"))
                .isNotEqualTo(ClusterIdentity.normalise("http://broker-1:8261/console/jolokia"))
                .isNotEqualTo(ClusterIdentity.normalise("https://broker-1:8161/console/jolokia"))
                .isNotEqualTo(ClusterIdentity.normalise("http://broker-2:8161/console/jolokia"));
        assertThat(ClusterIdentity.normalise("not a url")).isEqualTo("not a url");
    }

    @Test
    void aSharedNodeIdIsTheSameClusterWhateverTheUrl() {
        List<BrokerNodeEntity> registered = List.of(
                node(prod, "artemis-primary:61616", PAIR, null),
                node(prod, "localhost:8161", PAIR, "http://localhost:8161/console/jolokia"));

        assertThat(identity(Set.of(PAIR), "http://127.0.0.1:8161/console/jolokia")
                        .overlapWith(registered, names))
                .contains(new Overlap(prod, "prod-emea", List.of("artemis-primary:61616", "localhost:8161")));
    }

    @Test
    void aPartialOverlapNamesOnlyTheNodesThatOverlap() {
        List<BrokerNodeEntity> registered =
                List.of(node(prod, "broker-a:61616", PAIR, null), node(prod, "broker-b:61616", "b-id", null));

        assertThat(identity(Set.of(PAIR, OTHER), "http://broker-c:8161/console/jolokia")
                        .overlapWith(registered, names))
                .contains(new Overlap(prod, "prod-emea", List.of("broker-a:61616")));
    }

    @Test
    void withoutANodeIdTheUrlNormalFormDecides() {
        List<BrokerNodeEntity> registered =
                List.of(node(prod, "broker-1:8161", null, "http://broker-1:8161/console/jolokia"));

        assertThat(identity(Set.of(), "HTTP://BROKER-1:8161/console/jolokia/").overlapWith(registered, names))
                .contains(new Overlap(prod, "prod-emea", List.of("broker-1:8161")));
        assertThat(identity(Set.of(), "http://broker-1:8261/console/jolokia").overlapWith(registered, names))
                .isEmpty();
    }

    @Test
    void differentBrokersDoNotOverlap() {
        List<BrokerNodeEntity> registered =
                List.of(node(prod, "broker-1:8161", PAIR, "http://broker-1:8161/console/jolokia"));

        assertThat(identity(Set.of(OTHER), "http://broker-2:8161/console/jolokia")
                        .overlapWith(registered, names))
                .isEmpty();
    }

    @Test
    void theClusterHoldingMostOfTheBrokersIsNamed() {
        List<BrokerNodeEntity> registered =
                List.of(node(staging, "a", PAIR, null), node(prod, "b", OTHER, null), node(prod, "c", OTHER, null));

        assertThat(identity(Set.of(PAIR, OTHER)).overlapWith(registered, names))
                .map(Overlap::clusterId)
                .contains(prod);
    }

    @Test
    void aRegisteredClusterClaimsItsNodeIdsAndTheUrlOfANodeWithoutOne() {
        List<BrokerNodeEntity> registered = List.of(
                node(prod, "artemis-primary:61616", PAIR, null),
                node(prod, "localhost:8161", PAIR, "http://localhost:8161/console/jolokia"),
                node(prod, "broker-2:8161", null, "HTTP://Broker-2:8161/console/jolokia/"),
                node(prod, "broker-3:61616", null, null));

        assertThat(ClusterIdentity.claimsOf(registered))
                .containsEntry(ClusterIdentity.NODE_ID, Set.of(PAIR))
                .containsEntry(ClusterIdentity.URL, Set.of("http://broker-2:8161/console/jolokia"));
    }

    @Test
    void claimsEveryNodeIdAndTheUrlOfASeedWithoutOne() {
        ClusterIdentity identity = new ClusterIdentity(
                Set.of(PAIR),
                Set.of("http://a:8161/console/jolokia", "http://b:8161/console/jolokia"),
                Set.of("http://b:8161/console/jolokia"));

        assertThat(identity.claims())
                .containsEntry(ClusterIdentity.NODE_ID, Set.of(PAIR))
                .containsEntry(ClusterIdentity.URL, Set.of("http://b:8161/console/jolokia"));
    }
}
