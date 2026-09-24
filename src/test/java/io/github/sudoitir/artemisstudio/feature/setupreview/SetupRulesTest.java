package io.github.sudoitir.artemisstudio.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The catalogue over the attribute shapes Artemis 2.44 reports (ADR-0106, design D8). */
class SetupRulesTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String PRIMARY_QUORUM = "Replication Primary w/quorum voting";
    private static final String BACKUP_QUORUM = "Replication Backup w/quorum voting";

    /** A healthy clustered node: persistence, security, a DLA, TLS, redistribution on, one peer seen. */
    private static final class Node {
        final UUID id = UUID.randomUUID();
        String name;
        String nodeId;
        boolean live = true;
        final ObjectNode broker = MAPPER.createObjectNode();
        ObjectNode settings = MAPPER.createObjectNode();
        final Map<String, JsonNode> connections = new LinkedHashMap<>();
        String unavailable;

        Node(String name, String nodeId, String haPolicy) {
            this.name = name;
            this.nodeId = nodeId;
            broker.put("HAPolicy", haPolicy);
            broker.put("NodeID", nodeId);
            broker.put("Clustered", true);
            broker.put("Version", "2.44.0");
            broker.put("PersistenceEnabled", true);
            broker.put("SecurityEnabled", true);
            broker.put("MaxDiskUsage", 90);
            broker.putArray("ClusterConnectionNames").add("my-cluster");
            broker.put(
                    "ConnectorsAsJSON",
                    "[{\"name\":\"self\",\"factoryClassName\":\"org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory\",\"params\":{\"host\":\""
                            + name + ".internal\",\"port\":61616}}]");
            broker.put(
                    "AcceptorsAsJSON",
                    "[{\"name\":\"artemis\",\"factoryClassName\":\"org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory\",\"params\":{\"sslEnabled\":\"true\"}}]");
            settings.put("deadLetterAddress", "DLQ");
            settings.put("expiryAddress", "ExpiryQueue");
            settings.put("redistributionDelay", 0);
            ObjectNode cc = MAPPER.createObjectNode();
            cc.put("Started", true);
            cc.put("MessageLoadBalancingType", "ON_DEMAND");
            cc.put("MaxHops", 1);
            cc.put("DuplicateDetection", true);
            cc.putObject("Nodes");
            connections.put("my-cluster", cc);
        }

        Node sees(Node... others) {
            ObjectNode nodes = (ObjectNode) connections.get("my-cluster").get("Nodes");
            for (Node o : others) {
                nodes.put(o.nodeId, "tcp://" + o.name + ":61616");
            }
            return this;
        }

        Node passive() {
            live = false;
            broker.put("Active", false);
            return this;
        }

        NodeRead read() {
            if (unavailable != null) {
                return NodeRead.unreadable(id, name, nodeId, live, true, unavailable);
            }
            return new NodeRead(id, name, nodeId, live, true, null, broker, settings, null, connections, null);
        }
    }

    private static SetupRules.Result evaluate(Node... nodes) {
        List<NodeRead> reads = new ArrayList<>();
        for (Node n : nodes) {
            reads.add(n.read());
        }
        return SetupRules.evaluate(reads, MAPPER);
    }

    private static List<String> codes(SetupRules.Result r) {
        return r.findings().stream().map(Finding::code).toList();
    }

    private static Finding only(SetupRules.Result r, String code) {
        return r.findings().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theDevPairIsACriticalSinglePairFindingWithTheNetworkCheckCaveat() {
        Node primary = new Node("primary", "pair-1", PRIMARY_QUORUM);
        Node backup = new Node("backup", "pair-1", BACKUP_QUORUM).passive();

        SetupRules.Result r = evaluate(primary, backup);

        Finding f = only(r, "HA_SINGLE_PAIR_QUORUM");
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.subject()).isEqualTo(SetupRules.CLUSTER);
        assertThat(f.snippet()).contains("<manager>").contains("network-check-list");
        assertThat(f.caveats()).anyMatch(c -> c.contains("network-check-list"));
        assertThat(r.findings()).first().extracting(Finding::code).isEqualTo("HA_SINGLE_PAIR_QUORUM");
        assertThat(codes(r)).doesNotContain("HA_BACKUP_MISSING", "HA_POLICY_MISMATCH");
    }

    @Test
    void aLockManagerPairIsNotAQuorumFinding() {
        Node primary = new Node("primary", "pair-1", "Replication Primary w/lock manager");
        Node backup = new Node("backup", "pair-1", "Replication Backup w/lock manager").passive();

        assertThat(codes(evaluate(primary, backup))).doesNotContain("HA_SINGLE_PAIR_QUORUM", "HA_TWO_PRIMARY_QUORUM");
    }

    @Test
    void twoPairsWarnAndThreePairsPass() {
        Node p1 = new Node("p1", "a", PRIMARY_QUORUM);
        Node b1 = new Node("b1", "a", BACKUP_QUORUM).passive();
        Node p2 = new Node("p2", "b", PRIMARY_QUORUM);
        Node b2 = new Node("b2", "b", BACKUP_QUORUM).passive();
        p1.sees(p2);
        p2.sees(p1);
        assertThat(only(evaluate(p1, b1, p2, b2), "HA_TWO_PRIMARY_QUORUM").severity())
                .isEqualTo(Severity.WARNING);

        Node p3 = new Node("p3", "c", PRIMARY_QUORUM);
        Node b3 = new Node("b3", "c", BACKUP_QUORUM).passive();
        p1.sees(p3);
        p2.sees(p3);
        p3.sees(p1, p2);
        assertThat(codes(evaluate(p1, b1, p2, b2, p3, b3)))
                .doesNotContain("HA_SINGLE_PAIR_QUORUM", "HA_TWO_PRIMARY_QUORUM", "CLUSTER_MEMBERSHIP_INCOMPLETE");
    }

    @Test
    void primariesTheClusterConnectionReportsCountEvenWhenStudioHasNotRegisteredThem() {
        Node primary = new Node("primary", "pair-1", PRIMARY_QUORUM);
        Node backup = new Node("backup", "pair-1", BACKUP_QUORUM).passive();
        ((ObjectNode) primary.connections.get("my-cluster").get("Nodes")).put("x", "tcp://x:61616");
        ((ObjectNode) primary.connections.get("my-cluster").get("Nodes")).put("y", "tcp://y:61616");

        assertThat(codes(evaluate(primary, backup))).doesNotContain("HA_SINGLE_PAIR_QUORUM", "HA_TWO_PRIMARY_QUORUM");
    }

    @Test
    void aPrimaryWithoutItsBackupAndAMismatchedPair() {
        Node alone = new Node("alone", "solo", PRIMARY_QUORUM);
        assertThat(codes(evaluate(alone))).contains("HA_BACKUP_MISSING").doesNotContain("HA_SINGLE_PAIR_QUORUM");

        Node primary = new Node("primary", "pair-1", PRIMARY_QUORUM);
        Node backup = new Node("backup", "pair-1", "Shared Store Backup").passive();
        Finding f = only(evaluate(primary, backup), "HA_POLICY_MISMATCH");
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.evidence()).hasSize(2);
    }

    @Test
    void anUnknownHaPolicyIsNotAssessedNotGuessed() {
        Node odd = new Node("odd", "n", "Something New In 3.0");
        SetupRules.Result r = evaluate(odd);
        assertThat(codes(r)).noneMatch(c -> c.startsWith("HA_"));
        assertThat(r.notAssessed())
                .anyMatch(n -> n.code().equals("HA_*") && n.reason().contains("Something New"));
    }

    @Test
    void anUnreachableLiveNodeSkipsClusterRulesAndIsListed() {
        Node primary = new Node("primary", "pair-1", PRIMARY_QUORUM);
        Node other = new Node("other", "pair-2", PRIMARY_QUORUM);
        other.unavailable = "Nothing answered at this address.";

        SetupRules.Result r = evaluate(primary, other);

        assertThat(r.clusterEvaluated()).isFalse();
        assertThat(r.evaluated())
                .contains(primary.read().subject())
                .doesNotContain(other.read().subject(), SetupRules.CLUSTER);
        assertThat(r.notAssessed())
                .anyMatch(n -> n.subject().equals(other.read().subject()));
        assertThat(codes(r)).doesNotContain("CLUSTER_VERSION_SKEW", "HA_SINGLE_PAIR_QUORUM");
    }

    @Test
    void redistributionLeftAtItsDefaultStrandsMessages() {
        Node a = new Node("a", "a", "Primary Only");
        Node b = new Node("b", "b", "Primary Only");
        a.sees(b);
        b.sees(a);
        a.settings.remove("redistributionDelay"); // absent is the default, -1

        Finding f = only(evaluate(a, b), "CLUSTER_STRANDED_MESSAGES");
        assertThat(f.subject()).isEqualTo(a.read().subject());
        assertThat(f.appliable()).isTrue();
        assertThat(f.snippet()).contains("<redistribution-delay>0</redistribution-delay>");
    }

    @Test
    void aMemberTheClusterConnectionDoesNotSeeIsNamed() {
        Node a = new Node("a", "a", "Primary Only");
        Node b = new Node("b", "b", "Primary Only");
        Node c = new Node("c", "c", "Primary Only");
        a.sees(b);
        b.sees(a, c);
        c.sees(a, b);

        Finding f = only(evaluate(a, b, c), "CLUSTER_MEMBERSHIP_INCOMPLETE");
        assertThat(f.subject()).isEqualTo(a.read().subject());
        assertThat(f.evidence())
                .singleElement()
                .satisfies(e -> assertThat(e.value()).startsWith("c "));
    }

    @Test
    void clusteringMistakes() {
        Node a = new Node("a", "a", "Primary Only");
        Node b = new Node("b", "b", "Primary Only");
        a.sees(b);
        b.sees(a);
        ObjectNode cc = (ObjectNode) a.connections.get("my-cluster");
        cc.put("MessageLoadBalancingType", "OFF");
        cc.put("MaxHops", 0);
        cc.put("DuplicateDetection", false);
        cc.put("Started", false);
        b.broker.put("Version", "2.43.0");
        b.broker.put("Clustered", false);
        b.broker.put(
                "ConnectorsAsJSON",
                "[{\"name\":\"netty\",\"factoryClassName\":\"x.NettyConnectorFactory\",\"params\":{\"port\":61616}}]");
        b.broker.put("Clustered", true);
        b.broker.putArray("ClusterConnectionNames");

        List<String> codes = codes(evaluate(a, b));

        assertThat(codes)
                .contains(
                        "CLUSTER_LOAD_BALANCING_OFF",
                        "CLUSTER_MAX_HOPS_ZERO",
                        "CLUSTER_NO_DUPLICATE_DETECTION",
                        "CLUSTER_CONNECTION_STOPPED",
                        "CLUSTER_VERSION_SKEW",
                        "CLUSTER_NOT_CLUSTERED",
                        "CLUSTER_LOOPBACK_CONNECTOR",
                        "HA_NONE");
    }

    @Test
    void durabilityMessageSafetyAndSecurity() {
        Node n = new Node("n", "n", "Primary Only");
        n.broker.put("PersistenceEnabled", false);
        n.broker.put("MaxDiskUsage", -1);
        n.broker.put("SecurityEnabled", false);
        n.broker.put(
                "AcceptorsAsJSON",
                "[{\"name\":\"artemis\",\"factoryClassName\":\"x.NettyAcceptorFactory\",\"params\":{}},"
                        + "{\"name\":\"invm\",\"factoryClassName\":\"x.InVMAcceptorFactory\",\"params\":{}}]");
        n.settings = MAPPER.createObjectNode();
        n.settings.put("addressFullMessagePolicy", "DROP");

        SetupRules.Result r = evaluate(n);

        assertThat(codes(r))
                .contains(
                        "DURABILITY_PERSISTENCE_OFF",
                        "DURABILITY_DISK_UNBOUNDED",
                        "SECURITY_DISABLED",
                        "SECURITY_PLAINTEXT_ACCEPTOR",
                        "MESSAGES_NO_DLA",
                        "MESSAGES_NO_EXPIRY_ADDRESS",
                        "MESSAGES_DROP_WHEN_FULL")
                .doesNotContain("MESSAGES_INFINITE_REDELIVERY", "HA_NONE");
        assertThat(only(r, "MESSAGES_NO_DLA").impact()).contains("10 times");
        assertThat(only(r, "SECURITY_PLAINTEXT_ACCEPTOR").evidence()).hasSize(1);
        assertThat(r.findings()).first().extracting(Finding::severity).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void unlimitedRedeliveryIsItsOwnFinding() {
        Node n = new Node("n", "n", "Primary Only");
        n.settings.put("maxDeliveryAttempts", -1);
        assertThat(codes(evaluate(n))).contains("MESSAGES_INFINITE_REDELIVERY").doesNotContain("MESSAGES_NO_DLA");
    }

    @Test
    void aHealthyClusteredPairProducesNothingButTheQuorumFinding() {
        Node primary = new Node("primary", "pair-1", PRIMARY_QUORUM);
        Node backup = new Node("backup", "pair-1", BACKUP_QUORUM).passive();
        assertThat(codes(evaluate(primary, backup))).containsExactly("HA_SINGLE_PAIR_QUORUM");
    }

    @Test
    void everyCodeIsInTheCatalogue() {
        assertThat(SetupRules.CODES).doesNotHaveDuplicates().hasSize(22);
    }

    @Test
    void haPolicyStrings() {
        assertThat(SetupRules.haPolicy(PRIMARY_QUORUM))
                .extracting(SetupRules.HaPolicy::family, SetupRules.HaPolicy::side)
                .containsExactly(SetupRules.HaFamily.REPLICATION_QUORUM, SetupRules.HaSide.PRIMARY);
        assertThat(SetupRules.haPolicy("Replication Backup w/lock manager").family())
                .isEqualTo(SetupRules.HaFamily.REPLICATION_LOCK_MANAGER);
        assertThat(SetupRules.haPolicy("Shared Store Primary").family()).isEqualTo(SetupRules.HaFamily.SHARED_STORE);
        assertThat(SetupRules.haPolicy("Primary Only").family()).isEqualTo(SetupRules.HaFamily.PRIMARY_ONLY);
        assertThat(SetupRules.haPolicy("Colocated").family()).isEqualTo(SetupRules.HaFamily.COLOCATED);
        assertThat(SetupRules.haPolicy(null).family()).isEqualTo(SetupRules.HaFamily.UNKNOWN);
    }

    @Test
    void readerNamesTheClusterConnectionFromItsObjectName() {
        assertThat(
                        SetupReader.name(
                                "org.apache.activemq.artemis:broker=\"primary\",component=cluster-connections,name=\"studio-dev\""))
                .isEqualTo("studio-dev");
    }
}
