package io.github.sudoitir.artemisstudio.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The branches of the rule catalogue the happy-path cases in {@link SetupRulesTest} never reach. */
class SetupRulesEdgeCasesTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String QUORUM_PRIMARY = "Replication Primary w/quorum voting";

    /** A readable, healthy, clustered node; the tests break one thing at a time. */
    private static final class N {
        final UUID id = UUID.randomUUID();
        final String name;
        String nodeId;
        boolean live = true;
        boolean manageable = true;
        String unavailable;
        ObjectNode broker = MAPPER.createObjectNode();
        JsonNode settings = MAPPER.createObjectNode();
        String settingsError;
        Map<String, JsonNode> connections = new LinkedHashMap<>();
        String connectionsError;

        N(String name, String nodeId, String haPolicy) {
            this.name = name;
            this.nodeId = nodeId;
            broker.put("HAPolicy", haPolicy);
            broker.put("Clustered", true);
            broker.put("PersistenceEnabled", true);
            broker.put("SecurityEnabled", true);
            broker.put("Version", "2.44.0");
            broker.putArray("ClusterConnectionNames").add("cc");
            ((ObjectNode) settings).put("deadLetterAddress", "DLQ");
            ((ObjectNode) settings).put("expiryAddress", "Expiry");
            ((ObjectNode) settings).put("redistributionDelay", 0);
            ObjectNode cc = MAPPER.createObjectNode();
            cc.put("Started", true);
            cc.put("MessageLoadBalancingType", "ON_DEMAND");
            cc.put("MaxHops", 1);
            cc.put("DuplicateDetection", true);
            cc.putObject("Nodes");
            connections.put("cc", cc);
        }

        N with(Consumer<N> change) {
            change.accept(this);
            return this;
        }

        ObjectNode cc() {
            return (ObjectNode) connections.get("cc");
        }

        N sees(N... others) {
            ObjectNode nodes = (ObjectNode) cc().get("Nodes");
            for (N o : others) {
                nodes.put(o.nodeId, "tcp://" + o.name);
            }
            return this;
        }

        NodeRead read() {
            if (unavailable != null || !manageable) {
                return NodeRead.unreadable(id, name, nodeId, live, manageable, unavailable);
            }
            return new NodeRead(
                    id, name, nodeId, live, true, null, broker, settings, settingsError, connections, connectionsError);
        }
    }

    private static SetupRules.Result evaluate(N... nodes) {
        List<NodeRead> reads = new ArrayList<>();
        for (N n : nodes) {
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
                .orElseThrow(() -> new AssertionError(code + " not among " + codes(r)));
    }

    /** Two healthy unpaired nodes that see each other: no finding of any kind. */
    private static N[] cluster() {
        N a = new N("a", "id-a", "Primary Only");
        N b = new N("b", "id-b", "Primary Only");
        a.sees(b);
        b.sees(a);
        return new N[] {a, b};
    }

    // ---- HA policy strings ---------------------------------------------------

    @Test
    void oldLiveAndMasterSpellingsAreThePrimarySideAndBackupWinsOverBoth() {
        assertThat(SetupRules.haPolicy("Shared Store Live").side()).isEqualTo(SetupRules.HaSide.PRIMARY);
        assertThat(SetupRules.haPolicy("Shared Store Master").side()).isEqualTo(SetupRules.HaSide.PRIMARY);
        assertThat(SetupRules.haPolicy("Shared Store Backup").side()).isEqualTo(SetupRules.HaSide.BACKUP);
        assertThat(SetupRules.haPolicy("Shared Store").side()).isEqualTo(SetupRules.HaSide.UNKNOWN);
        assertThat(SetupRules.haPolicy("Primary Only").side()).isEqualTo(SetupRules.HaSide.NONE);
    }

    @Test
    void replicationVariantsAreToldApartByTheirCoordination() {
        assertThat(SetupRules.haPolicy("Replication Primary w/pluggable lock").family())
                .isEqualTo(SetupRules.HaFamily.REPLICATION_LOCK_MANAGER);
        assertThat(SetupRules.haPolicy("Replicated Primary w/quorum").family())
                .isEqualTo(SetupRules.HaFamily.REPLICATION_QUORUM);
        assertThat(SetupRules.haPolicy("Replication Primary").family())
                .as("replication with an unstated coordination is not guessed")
                .isEqualTo(SetupRules.HaFamily.UNKNOWN);
        assertThat(SetupRules.haPolicy("  ").family()).isEqualTo(SetupRules.HaFamily.UNKNOWN);
        assertThat(SetupRules.haPolicy("Something Else Entirely").side()).isEqualTo(SetupRules.HaSide.UNKNOWN);
    }

    @Test
    void onlyAPrimaryOfAFailoverFamilyExpectsABackup() {
        assertThat(SetupRules.haPolicy("Shared Store Primary").expectsBackup()).isTrue();
        assertThat(SetupRules.haPolicy("Shared Store Backup").expectsBackup()).isFalse();
        assertThat(SetupRules.haPolicy("Primary Only").expectsBackup()).isFalse();
        assertThat(SetupRules.haPolicy("Replication Primary w/lock manager").expectsBackup())
                .isTrue();
    }

    // ---- what could not be read ------------------------------------------------

    @Test
    void whenNoNodeCanBeReadTheWholeReviewSaysSoAndNothingIsEvaluated() {
        N down = new N("down", "id-a", "Primary Only").with(n -> {
            n.unavailable = "refused";
            n.live = false;
        });

        SetupRules.Result r = evaluate(down);

        assertThat(r.clusterEvaluated()).isFalse();
        assertThat(r.findings()).isEmpty();
        assertThat(r.evaluated()).isEmpty();
        assertThat(r.notAssessed())
                .anyMatch(n ->
                        n.subject().equals(SetupRules.CLUSTER) && n.reason().equals("No node could be read."))
                .anyMatch(n ->
                        n.subject().equals(down.read().subject()) && n.reason().equals("refused"));
    }

    @Test
    void aLiveNodeThatDidNotAnswerBlocksTheClusterWideRulesOnly() {
        N a = new N("a", "id-a", "Primary Only");
        N b = new N("b", "id-b", "Primary Only").with(n -> n.unavailable = "timeout");

        SetupRules.Result r = evaluate(a, b);

        assertThat(r.clusterEvaluated()).isFalse();
        assertThat(r.evaluated()).contains(a.read().subject());
        assertThat(r.notAssessed())
                .anyMatch(n ->
                        n.subject().equals(SetupRules.CLUSTER) && n.reason().contains("Not every live node answered"));
    }

    @Test
    void aStandbyThatDidNotAnswerDoesNotBlockTheClusterRulesAndAnUnmanageableNodeIsNotListed() {
        N a = new N("a", "id-a", "Primary Only");
        N standby = new N("s", "id-s", "Primary Only").with(n -> {
            n.unavailable = "timeout";
            n.live = false;
        });
        N unmanaged = new N("u", "id-u", "Primary Only").with(n -> {
            n.manageable = false;
            n.live = false;
        });

        SetupRules.Result r = evaluate(a, standby, unmanaged);

        assertThat(r.clusterEvaluated()).isTrue();
        assertThat(r.notAssessed())
                .anyMatch(n -> n.subject().equals(standby.read().subject()))
                .noneMatch(n -> n.subject().equals(unmanaged.read().subject()));
    }

    @Test
    void aNodeThatReportedNoHaPolicyIsNotAssessedForItAndSaysWhy() {
        N n = new N("n", "id-n", "Primary Only").with(x -> x.broker.remove("HAPolicy"));

        SetupRules.Result r = evaluate(n);

        assertThat(r.notAssessed())
                .anyMatch(a -> a.code().equals("HA_*") && a.reason().equals("The node did not report HAPolicy."));
    }

    @Test
    void clusterRulesForANodeWhoseConnectionsCouldNotBeReadAreNotAssessed() {
        N[] pair = cluster();
        pair[0].connections = null;
        pair[0].connectionsError = "403";

        SetupRules.Result r = evaluate(pair);

        assertThat(r.notAssessed())
                .anyMatch(a -> a.code().equals("CLUSTER_*")
                        && a.subject().equals(pair[0].read().subject())
                        && "403".equals(a.reason()));
    }

    @Test
    void messageRulesForANodeWhoseAddressSettingsCouldNotBeReadAreNotAssessed() {
        N n = new N("n", "id-n", "Primary Only").with(x -> {
            x.settings = null;
            x.settingsError = "no settings";
        });

        SetupRules.Result r = evaluate(n);

        assertThat(r.notAssessed()).anyMatch(a -> a.code().equals("MESSAGES_*") && "no settings".equals(a.reason()));
        assertThat(codes(r)).noneMatch(c -> c.startsWith("MESSAGES_"));
    }

    @Test
    void aHealthyUnpairedClusterOnlyNotesThatItHasNoHa() {
        assertThat(codes(evaluate(cluster()))).containsOnly("HA_NONE");
    }

    // ---- HA pairs ---------------------------------------------------------------

    @Test
    void aStandbyPrimaryOrOneWithoutANodeIdIsNotAskedForABackup() {
        N standby = new N("s", "id-s", QUORUM_PRIMARY).with(n -> n.live = false);
        N noId = new N("x", null, QUORUM_PRIMARY);

        assertThat(codes(evaluate(standby))).doesNotContain("HA_BACKUP_MISSING");
        assertThat(codes(evaluate(noId))).doesNotContain("HA_BACKUP_MISSING", "HA_POLICY_MISMATCH");
    }

    @Test
    void anEndpointPairOfDifferentFamiliesOrTwoPrimariesOrTwoBackupsIsAMismatch() {
        N quorum = new N("p", "pair", QUORUM_PRIMARY);
        N shared = new N("b", "pair", "Shared Store Backup").with(n -> n.live = false);
        assertThat(only(evaluate(quorum, shared), "HA_POLICY_MISMATCH").title()).contains("different HA policies");

        N p1 = new N("p1", "pair", "Shared Store Primary");
        N p2 = new N("p2", "pair", "Shared Store Primary");
        assertThat(only(evaluate(p1, p2), "HA_POLICY_MISMATCH").title()).contains("both configured as primary");

        N b1 = new N("b1", "pair", "Shared Store Backup").with(n -> n.live = false);
        N b2 = new N("b2", "pair", "Shared Store Backup").with(n -> n.live = false);
        assertThat(only(evaluate(b1, b2), "HA_POLICY_MISMATCH").title()).contains("both configured as backup");
    }

    @Test
    void aWellFormedSharedStorePairIsNotAMismatch() {
        N p = new N("p", "pair", "Shared Store Primary");
        N b = new N("b", "pair", "Shared Store Backup").with(n -> n.live = false);

        assertThat(codes(evaluate(p, b))).doesNotContain("HA_POLICY_MISMATCH", "HA_BACKUP_MISSING");
    }

    @Test
    void aPairWithAnUnrecognisedHalfIsNotAMismatchOnItsOwn() {
        N p = new N("p", "pair", "Shared Store Primary");
        N odd = new N("o", "pair", "Brand New Policy").with(n -> n.live = false);

        assertThat(codes(evaluate(p, odd))).doesNotContain("HA_POLICY_MISMATCH");
    }

    @Test
    void aQuorumPairWithNoBackupSeenIsNotASinglePairFinding() {
        N lone = new N("p", "pair", QUORUM_PRIMARY);
        lone.sees();

        assertThat(codes(evaluate(lone))).doesNotContain("HA_SINGLE_PAIR_QUORUM");
    }

    // ---- durability and versions ---------------------------------------------------

    @Test
    void diskLimitsAreOnlyFlaggedWhenUnboundedAndNumeric() {
        for (int unbounded : new int[] {-1, 100, 150}) {
            N n = new N("n", "id-n", "Primary Only").with(x -> x.broker.put("MaxDiskUsage", unbounded));
            assertThat(codes(evaluate(n))).as("MaxDiskUsage=" + unbounded).contains("DURABILITY_DISK_UNBOUNDED");
        }
        N bounded = new N("n", "id-n", "Primary Only").with(x -> x.broker.put("MaxDiskUsage", 90));
        assertThat(codes(evaluate(bounded))).doesNotContain("DURABILITY_DISK_UNBOUNDED");
        N text = new N("n", "id-n", "Primary Only").with(x -> x.broker.put("MaxDiskUsage", "lots"));
        assertThat(codes(evaluate(text))).doesNotContain("DURABILITY_DISK_UNBOUNDED");
        N absent = new N("n", "id-n", "Primary Only");
        assertThat(codes(evaluate(absent))).doesNotContain("DURABILITY_DISK_UNBOUNDED");
    }

    @Test
    void versionSkewNeedsTwoDifferentReportedVersions() {
        N[] pair = cluster();
        pair[1].broker.remove("Version");
        assertThat(codes(evaluate(pair))).doesNotContain("CLUSTER_VERSION_SKEW");

        N[] mixed = cluster();
        mixed[1].broker.put("Version", "2.43.0");
        Finding f = only(evaluate(mixed), "CLUSTER_VERSION_SKEW");
        assertThat(f.title()).contains("2.44.0").contains("2.43.0");
        assertThat(f.evidence()).hasSize(2);
    }

    // ---- clustering -------------------------------------------------------------------

    @Test
    void aNodeWithoutAClusterConnectionOrNotClusteredIsFlaggedWithWhatItReported() {
        N[] noNames = cluster();
        noNames[0].broker.putArray("ClusterConnectionNames");
        assertThat(only(evaluate(noNames), "CLUSTER_NOT_CLUSTERED").evidence())
                .anyMatch(e -> e.value().equals("[]"));

        N[] notReported = cluster();
        notReported[0].broker.remove("ClusterConnectionNames");
        notReported[0].broker.put("Clustered", "false");
        assertThat(only(evaluate(notReported), "CLUSTER_NOT_CLUSTERED").evidence())
                .anyMatch(e -> e.value().equals("(not reported)"));

        N[] fine = cluster();
        fine[0].broker.put("Clustered", "maybe");
        assertThat(codes(evaluate(fine))).doesNotContain("CLUSTER_NOT_CLUSTERED");
    }

    @Test
    void clusterRulesNeedMoreThanOneLogicalNode() {
        N solo = new N("solo", "id-a", "Primary Only").with(n -> {
            n.broker.put("Clustered", false);
            n.cc().put("MaxHops", 0);
        });

        assertThat(codes(evaluate(solo))).noneMatch(c -> c.startsWith("CLUSTER_"));
    }

    @Test
    void clusterConnectionAttributesOnlyRaiseTheirFindingWhenTheyAreReallyWrong() {
        N[] pair = cluster();
        pair[0].cc().put("MaxHops", "zero");
        pair[0].cc().put("Started", "true");
        pair[0].cc().put("DuplicateDetection", "yes");
        assertThat(codes(evaluate(pair))).containsOnly("HA_NONE");

        N[] wrong = cluster();
        wrong[0].cc().put("Started", "false");
        wrong[0].cc().put("DuplicateDetection", "FALSE");
        wrong[0].cc().put("MessageLoadBalancingType", "off");
        assertThat(codes(evaluate(wrong)))
                .contains("CLUSTER_CONNECTION_STOPPED", "CLUSTER_NO_DUPLICATE_DETECTION", "CLUSTER_LOAD_BALANCING_OFF");
    }

    @Test
    void aStandbyNodeIsNotFlaggedForStoppedConnectionsOrStrandedMessages() {
        N[] pair = cluster();
        pair[0].live = false;
        pair[0].cc().put("Started", false);
        ((ObjectNode) pair[0].settings).remove("redistributionDelay");

        assertThat(codes(evaluate(pair))).doesNotContain("CLUSTER_CONNECTION_STOPPED", "CLUSTER_STRANDED_MESSAGES");
    }

    @Test
    void redistributionIsOnlyStrandedForTheBalancingModesThatRelyOnIt() {
        N[] redistributing = cluster();
        redistributing[0].cc().put("MessageLoadBalancingType", "OFF_WITH_REDISTRIBUTION");
        ((ObjectNode) redistributing[0].settings).put("redistributionDelay", -1);
        assertThat(codes(evaluate(redistributing))).contains("CLUSTER_STRANDED_MESSAGES");

        N[] strict = cluster();
        strict[0].cc().put("MessageLoadBalancingType", "STRICT");
        ((ObjectNode) strict[0].settings).put("redistributionDelay", -1);
        assertThat(codes(evaluate(strict))).doesNotContain("CLUSTER_STRANDED_MESSAGES");

        N[] unbalanced = cluster();
        unbalanced[0].cc().remove("MessageLoadBalancingType");
        ((ObjectNode) unbalanced[0].settings).put("redistributionDelay", -1);
        assertThat(codes(evaluate(unbalanced))).doesNotContain("CLUSTER_STRANDED_MESSAGES");
    }

    @Test
    void aPeerThatIsStandbyOrHasNoNodeIdIsNotExpectedInTheClusterConnection() {
        N a = new N("a", "id-a", "Primary Only");
        N b = new N("b", "id-b", "Primary Only").with(n -> n.live = false);
        N c = new N("c", null, "Primary Only");
        N d = new N("d", "id-d", "Primary Only");
        a.sees(d);
        d.sees(a);
        c.sees(a, d);

        assertThat(codes(evaluate(a, b, c, d))).doesNotContain("CLUSTER_MEMBERSHIP_INCOMPLETE");
    }

    @Test
    void aClusterConnectionWithoutANodesObjectHasNothingToCompare() {
        N[] pair = cluster();
        pair[0].cc().remove("Nodes");

        assertThat(codes(evaluate(pair))).doesNotContain("CLUSTER_MEMBERSHIP_INCOMPLETE");
    }

    // ---- connectors and acceptors ---------------------------------------------------------

    private static N withConnectors(Object connectors) {
        N n = new N("n", "id-n", "Primary Only");
        if (connectors instanceof JsonNode json) {
            n.broker.set("ConnectorsAsJSON", json);
        } else if (connectors != null) {
            n.broker.put("ConnectorsAsJSON", connectors.toString());
        }
        return n;
    }

    @Test
    void connectorsThatAreMissingUnparseableOrNotAnArrayAreNotAssessedAndNotFlagged() {
        assertThat(codes(evaluate(withConnectors(null)))).doesNotContain("CLUSTER_LOOPBACK_CONNECTOR");
        assertThat(codes(evaluate(withConnectors("not json {")))).doesNotContain("CLUSTER_LOOPBACK_CONNECTOR");
        assertThat(codes(evaluate(withConnectors("{\"name\":\"x\"}")))).doesNotContain("CLUSTER_LOOPBACK_CONNECTOR");
        N nullNode = withConnectors(null);
        nullNode.broker.putNull("ConnectorsAsJSON");
        assertThat(codes(evaluate(nullNode))).doesNotContain("CLUSTER_LOOPBACK_CONNECTOR");
    }

    @Test
    void connectorsAreOnlyCheckedOnAClusteredNode() {
        N n = withConnectors("[{\"name\":\"c\",\"factoryClassName\":\"Netty\",\"params\":{\"host\":\"localhost\"}}]");
        n.broker.put("Clustered", false);

        assertThat(codes(evaluate(n))).doesNotContain("CLUSTER_LOOPBACK_CONNECTOR");
    }

    @Test
    void aConnectorWithNoHostIsFlaggedAndAnInVmOneIsSkipped() {
        N n = withConnectors("[{\"name\":\"noHost\",\"factoryClassName\":\"Netty\",\"params\":{}},"
                + "{\"name\":\"invm\",\"factoryClassName\":\"InVMConnectorFactory\",\"params\":{\"host\":\"localhost\"}},"
                + "{\"name\":\"fine\",\"factoryClassName\":\"Netty\",\"params\":{\"host\":\"broker-1.internal\"}}]");

        Finding f = only(evaluate(n), "CLUSTER_LOOPBACK_CONNECTOR");

        assertThat(f.evidence()).singleElement().satisfies(e -> {
            assertThat(e.key()).isEqualTo("connector noHost / host");
            assertThat(e.value()).contains("not set");
        });
    }

    @Test
    void connectorsGivenAsAnArrayNodeAreReadWithoutReparsing() {
        var array = MAPPER.createArrayNode();
        array.add(MAPPER.readTree("{\"name\":\"c\",\"factoryClassName\":\"Netty\",\"params\":{\"host\":\"0.0.0.0\"}}"));

        assertThat(codes(evaluate(withConnectors(array)))).contains("CLUSTER_LOOPBACK_CONNECTOR");
    }

    @Test
    void everyLoopbackAndWildcardSpellingIsRecognised() {
        for (String host : List.of(
                "localhost", "LocalHost", " 127.0.0.1 ", "127.5.5.5", "0.0.0.0", "::1", "[::1]", "::", "[::]")) {
            assertThat(SetupRules.isLoopbackOrWildcard(host)).as(host).isTrue();
        }
        assertThat(SetupRules.isLoopbackOrWildcard("broker-1.internal")).isFalse();
        assertThat(SetupRules.isLoopbackOrWildcard("10.0.0.5")).isFalse();
    }

    @Test
    void acceptorsAreFlaggedForMissingOrFalseTlsAndSkippedWhenUnreadable() {
        N n = new N("n", "id-n", "Primary Only");
        n.broker.put(
                "AcceptorsAsJSON",
                "[{\"name\":\"plain\",\"factoryClassName\":\"Netty\",\"params\":{}},"
                        + "{\"name\":\"off\",\"factoryClassName\":\"Netty\",\"params\":{\"sslEnabled\":\"false\"}},"
                        + "{\"name\":\"tls\",\"factoryClassName\":\"Netty\",\"params\":{\"sslEnabled\":\"TRUE\"}}]");

        Finding f = only(evaluate(n), "SECURITY_PLAINTEXT_ACCEPTOR");

        assertThat(f.evidence()).extracting(e -> e.value()).containsExactly("(not set)", "false");

        N missing = new N("m", "id-m", "Primary Only");
        assertThat(codes(evaluate(missing))).doesNotContain("SECURITY_PLAINTEXT_ACCEPTOR");
        N notArray = new N("x", "id-x", "Primary Only");
        notArray.broker.put("AcceptorsAsJSON", "\"nope\"");
        assertThat(codes(evaluate(notArray))).doesNotContain("SECURITY_PLAINTEXT_ACCEPTOR");
    }

    // ---- small helpers -------------------------------------------------------------------

    @Test
    void textIsNullForMissingNullAndBlankFields() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("blank", "  ");
        node.putNull("null");
        node.put("value", "v");

        assertThat(SetupRules.text(null, "value")).isNull();
        assertThat(SetupRules.text(node, "absent")).isNull();
        assertThat(SetupRules.text(node, "null")).isNull();
        assertThat(SetupRules.text(node, "blank")).isNull();
        assertThat(SetupRules.text(node, "value")).isEqualTo("v");
    }

    @Test
    void boolReadsBooleansAndTheirTextFormsAndNothingElse() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("real", true);
        node.put("yes", "TRUE");
        node.put("no", "False");
        node.put("odd", "maybe");
        node.putNull("null");

        assertThat(SetupRules.bool(null, "real")).isNull();
        assertThat(SetupRules.bool(node, "absent")).isNull();
        assertThat(SetupRules.bool(node, "null")).isNull();
        assertThat(SetupRules.bool(node, "real")).isTrue();
        assertThat(SetupRules.bool(node, "yes")).isTrue();
        assertThat(SetupRules.bool(node, "no")).isFalse();
        assertThat(SetupRules.bool(node, "odd")).isNull();
    }
}
