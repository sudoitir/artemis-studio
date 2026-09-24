package io.github.sudoitir.artemisstudio.feature.setupreview;

import io.github.sudoitir.artemisstudio.feature.setupreview.Finding.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The setup review's rule catalogue (ADR-0106, design D8). Pure: nodes in, findings out, no Spring
 * and no I/O, so every rule is pinned by a table test over the attribute shapes verified against
 * the Artemis 2.44 sources.
 *
 * <p>Three rules keep it honest:
 *
 * <ul>
 *   <li><b>Only what was read is judged.</b> A node that did not answer contributes no finding and
 *       is listed as unreviewed; a cluster-wide rule runs only when every live, manageable node
 *       answered.
 *   <li><b>Unknown is not assumed.</b> An HA policy string this catalogue does not recognise, or an
 *       attribute a broker did not report, is "not assessed" — never guessed into a pass or a
 *       fault.
 *   <li><b>What the API cannot see is said.</b> {@code network-check-list}, {@code quorum-size}
 *       and {@code vote-on-replication-failure} are not exposed over management; a finding that
 *       they could mitigate names them as a caveat.
 * </ul>
 */
public final class SetupRules {

    public static final String CLUSTER = "cluster";

    /** Every code this catalogue can produce, so the screen can say how much was checked. */
    public static final List<String> CODES = List.of(
            "HA_SINGLE_PAIR_QUORUM",
            "HA_TWO_PRIMARY_QUORUM",
            "HA_BACKUP_MISSING",
            "HA_POLICY_MISMATCH",
            "HA_NONE",
            "CLUSTER_NOT_CLUSTERED",
            "CLUSTER_CONNECTION_STOPPED",
            "CLUSTER_MEMBERSHIP_INCOMPLETE",
            "CLUSTER_LOOPBACK_CONNECTOR",
            "CLUSTER_LOAD_BALANCING_OFF",
            "CLUSTER_STRANDED_MESSAGES",
            "CLUSTER_MAX_HOPS_ZERO",
            "CLUSTER_VERSION_SKEW",
            "CLUSTER_NO_DUPLICATE_DETECTION",
            "DURABILITY_PERSISTENCE_OFF",
            "DURABILITY_DISK_UNBOUNDED",
            "MESSAGES_NO_DLA",
            "MESSAGES_INFINITE_REDELIVERY",
            "MESSAGES_NO_EXPIRY_ADDRESS",
            "MESSAGES_DROP_WHEN_FULL",
            "SECURITY_DISABLED",
            "SECURITY_PLAINTEXT_ACCEPTOR");

    /** Where a fix for an address setting can be applied from. */
    private static final String REDISTRIBUTION_SNIPPET = """
            <address-settings>
              <address-setting match="#">
                <redistribution-delay>0</redistribution-delay>
              </address-setting>
            </address-settings>""";

    private SetupRules() {}

    public record Result(
            List<Finding> findings, List<NotAssessed> notAssessed, boolean clusterEvaluated, Set<String> evaluated) {}

    /** The HA policy family a {@code HAPolicy} string names. */
    enum HaFamily {
        PRIMARY_ONLY,
        REPLICATION_QUORUM,
        REPLICATION_LOCK_MANAGER,
        SHARED_STORE,
        COLOCATED,
        UNKNOWN
    }

    enum HaSide {
        PRIMARY,
        BACKUP,
        NONE,
        UNKNOWN
    }

    record HaPolicy(String raw, HaFamily family, HaSide side) {
        boolean expectsBackup() {
            return side == HaSide.PRIMARY
                    && (family == HaFamily.REPLICATION_QUORUM
                            || family == HaFamily.REPLICATION_LOCK_MANAGER
                            || family == HaFamily.SHARED_STORE);
        }
    }

    /**
     * Parses {@code HAPolicy} as {@code HAPolicyConfiguration.TYPE} names it (2.44): "Primary Only",
     * "Replication Primary w/quorum voting", "Replication Backup w/lock manager", "Shared Store
     * Primary", "Colocated", … . Pre-2.30 brokers said "Live" for "Primary"; both are accepted.
     */
    static HaPolicy haPolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return new HaPolicy(raw, HaFamily.UNKNOWN, HaSide.UNKNOWN);
        }
        String s = raw.toLowerCase(Locale.ROOT);
        HaSide side = s.contains("backup")
                ? HaSide.BACKUP
                : (s.contains("primary") || s.contains("live") || s.contains("master"))
                        ? HaSide.PRIMARY
                        : HaSide.UNKNOWN;
        if (s.contains("only")) {
            return new HaPolicy(raw, HaFamily.PRIMARY_ONLY, HaSide.NONE);
        }
        if (s.contains("colocated")) {
            return new HaPolicy(raw, HaFamily.COLOCATED, HaSide.NONE);
        }
        if (s.contains("replication") && (s.contains("lock manager") || s.contains("pluggable"))) {
            return new HaPolicy(raw, HaFamily.REPLICATION_LOCK_MANAGER, side);
        }
        if (s.contains("replication") || s.contains("replicated")) {
            return new HaPolicy(raw, s.contains("quorum") ? HaFamily.REPLICATION_QUORUM : HaFamily.UNKNOWN, side);
        }
        if (s.contains("shared store")) {
            return new HaPolicy(raw, HaFamily.SHARED_STORE, side);
        }
        return new HaPolicy(raw, HaFamily.UNKNOWN, HaSide.UNKNOWN);
    }

    public static Result evaluate(List<NodeRead> nodes, ObjectMapper mapper) {
        Context ctx = new Context(nodes, mapper);
        for (NodeRead node : ctx.readable) {
            ctx.evaluated.add(node.subject());
            nodeRules(ctx, node);
        }
        for (NodeRead node : nodes) {
            if (!node.readable() && node.manageable()) {
                ctx.notAssessed.add(new NotAssessed("*", node.subject(), node.unavailableReason()));
            }
        }
        boolean clusterEvaluated =
                nodes.stream().noneMatch(n -> n.manageable() && n.live() && !n.readable()) && !ctx.readable.isEmpty();
        if (clusterEvaluated) {
            ctx.evaluated.add(CLUSTER);
            clusterRules(ctx);
        } else {
            ctx.notAssessed.add(new NotAssessed(
                    "*",
                    CLUSTER,
                    ctx.readable.isEmpty()
                            ? "No node could be read."
                            : "Not every live node answered, so cluster-wide rules were not evaluated."));
        }
        ctx.findings.sort(Comparator.comparing(Finding::severity)
                .thenComparing(Finding::category)
                .thenComparing(Finding::code)
                .thenComparing(Finding::subject));
        return new Result(
                List.copyOf(ctx.findings), List.copyOf(ctx.notAssessed), clusterEvaluated, Set.copyOf(ctx.evaluated));
    }

    // ------------------------------------------------------------------ cluster-wide rules

    private static void clusterRules(Context ctx) {
        quorum(ctx);
        versionSkew(ctx);
    }

    /**
     * {@code QuorumManager} skips the vote when the cluster has only ever had one primary
     * ({@code AMQ221083: ignoring quorum vote as max cluster size is 1}) and the backup promotes
     * itself; with two, one surviving primary decides every vote ({@code QuorumVoteServerConnect}
     * needs {@code size/2} votes at a size of two or less).
     */
    private static void quorum(Context ctx) {
        List<NodeRead> voting = ctx.readable.stream()
                .filter(n -> ctx.policy(n).family() == HaFamily.REPLICATION_QUORUM)
                .toList();
        if (voting.isEmpty()) {
            return;
        }
        boolean backupSeen = ctx.readable.stream().anyMatch(n -> ctx.policy(n).side() == HaSide.BACKUP);
        int studioPrimaries = ctx.studioNodeIds.size();
        Set<String> members = new TreeSet<>(ctx.studioNodeIds);
        for (NodeRead n : ctx.readable) {
            members.addAll(ctx.clusterMembers(n));
        }
        int primaries = members.size();
        List<Evidence> evidence = new ArrayList<>();
        for (NodeRead n : voting) {
            evidence.add(new Evidence(n.nodeName(), "HAPolicy", ctx.policy(n).raw()));
        }
        evidence.add(new Evidence(
                null, "Primaries registered in Studio (distinct NodeIDs)", Integer.toString(studioPrimaries)));
        evidence.add(new Evidence(null, "Primaries seen by the cluster connections", Integer.toString(primaries)));
        List<String> caveats = List.of(
                "A network pinger (<network-check-list>) makes a node that loses its network stop itself instead"
                        + " of promoting; the management API does not reveal whether one is configured.",
                "<quorum-size> and <vote-on-replication-failure> are not visible either.");
        String lockManager = """
                <!-- On both the primary and the backup; point connect-string at your ZooKeeper ensemble. -->
                <ha-policy>
                  <replication>
                    <primary>  <!-- <backup> on the backup -->
                      <manager>
                        <class-name>org.apache.activemq.artemis.lockmanager.zookeeper.CuratorDistributedLockManager</class-name>
                        <properties>
                          <property key="connect-string" value="zk1:2181,zk2:2181,zk3:2181"/>
                        </properties>
                      </manager>
                    </primary>
                  </replication>
                </ha-policy>

                <!-- Or, as a mitigation on every node: stop when the network is lost. -->
                <network-check-list>10.0.0.1,10.0.0.2</network-check-list>
                <network-check-period>5000</network-check-period>
                <network-check-timeout>1000</network-check-timeout>""";
        if (primaries <= 1 && backupSeen) {
            ctx.findings.add(new Finding(
                    "HA_SINGLE_PAIR_QUORUM",
                    Category.HIGH_AVAILABILITY,
                    Severity.CRITICAL,
                    CLUSTER,
                    "A single replication pair cannot win a quorum vote, so a network partition splits the brain",
                    "With one primary there is nobody to vote: Artemis skips the vote and the backup promotes"
                            + " itself whenever it loses its primary. If the primary is still running on the other"
                            + " side of a partition, both serve clients and their journals diverge.",
                    evidence,
                    "Coordinate the pair through a distributed lock manager (ZooKeeper), or run at least three"
                            + " primary/backup pairs so a majority can be reached. As a mitigation, configure a network"
                            + " pinger on both nodes.",
                    lockManager,
                    caveats,
                    false));
        } else if (primaries == 2) {
            ctx.findings.add(new Finding(
                    "HA_TWO_PRIMARY_QUORUM",
                    Category.HIGH_AVAILABILITY,
                    Severity.WARNING,
                    CLUSTER,
                    "Two primaries: one surviving primary decides every failover vote",
                    "A backup that loses its primary asks the one other primary. If that primary is on the backup's"
                            + " side of a partition, the backup promotes itself while its own primary may still be"
                            + " serving on the far side.",
                    evidence,
                    "Add a third primary/backup pair, or coordinate each pair through a distributed lock manager.",
                    lockManager,
                    caveats,
                    false));
        }
    }

    private static void versionSkew(Context ctx) {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (NodeRead n : ctx.readable) {
            String version = text(n.broker(), "Version");
            if (version != null) {
                byVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(n.nodeName());
            }
        }
        if (byVersion.size() <= 1) {
            return;
        }
        List<Evidence> evidence = new ArrayList<>();
        byVersion.forEach(
                (version, names) -> names.forEach(name -> evidence.add(new Evidence(name, "Version", version))));
        ctx.findings.add(new Finding(
                "CLUSTER_VERSION_SKEW",
                Category.CLUSTERING,
                Severity.WARNING,
                CLUSTER,
                "Nodes run different broker versions (" + String.join(", ", byVersion.keySet()) + ")",
                "Mixed versions are supported only during a rolling upgrade. Left in place, replication, the"
                        + " cluster protocol and journal formats can disagree in ways that surface at failover.",
                evidence,
                "Finish the rolling upgrade so every node runs the same version.",
                null,
                List.of(),
                false));
    }

    // ------------------------------------------------------------------ node rules

    private static void nodeRules(Context ctx, NodeRead n) {
        JsonNode b = n.broker();
        HaPolicy policy = ctx.policy(n);
        if (policy.family() == HaFamily.UNKNOWN) {
            ctx.notAssessed.add(new NotAssessed(
                    "HA_*",
                    n.subject(),
                    policy.raw() == null
                            ? "The node did not report HAPolicy."
                            : "The HA policy \"" + policy.raw() + "\" is not one this review recognises."));
        }
        backupMissing(ctx, n, policy);
        policyMismatch(ctx, n, policy);
        if (policy.family() == HaFamily.PRIMARY_ONLY && ctx.logicalNodes() >= 2) {
            ctx.findings.add(finding(
                    "HA_NONE",
                    Category.HIGH_AVAILABILITY,
                    Severity.INFO,
                    n,
                    "No high availability on " + n.nodeName(),
                    "Messages stored on this node are unavailable while it is down; the cluster redistributes new"
                            + " traffic but not what this node already holds.",
                    List.of(new Evidence(n.nodeName(), "HAPolicy", policy.raw())),
                    "Pair it with a replication backup (or a shared-store backup) if its messages must survive the"
                            + " node's loss.",
                    null,
                    List.of(),
                    false));
        }

        clustering(ctx, n, b);
        connectors(ctx, n, b);

        Boolean persistence = bool(b, "PersistenceEnabled");
        if (Boolean.FALSE.equals(persistence)) {
            ctx.findings.add(finding(
                    "DURABILITY_PERSISTENCE_OFF",
                    Category.DURABILITY,
                    Severity.CRITICAL,
                    n,
                    "Persistence is disabled on " + n.nodeName(),
                    "Every message, including durable ones, is held only in memory and lost on a restart or"
                            + " crash; a backup cannot replicate a journal that is never written.",
                    List.of(new Evidence(n.nodeName(), "PersistenceEnabled", "false")),
                    "Enable persistence.",
                    "<persistence-enabled>true</persistence-enabled>",
                    List.of(),
                    false));
        }
        JsonNode disk = b.get("MaxDiskUsage");
        if (disk != null && disk.isNumber() && (disk.asInt() < 0 || disk.asInt() >= 100)) {
            ctx.findings.add(finding(
                    "DURABILITY_DISK_UNBOUNDED",
                    Category.DURABILITY,
                    Severity.WARNING,
                    n,
                    "Disk usage is unbounded on " + n.nodeName(),
                    "The broker keeps paging until the disk is full. A full journal disk stops the broker, and can"
                            + " take the host's other services with it, instead of blocking producers early.",
                    List.of(new Evidence(n.nodeName(), "MaxDiskUsage", disk.asString())),
                    "Block producers before the disk fills; the default is 90 percent.",
                    "<max-disk-usage>90</max-disk-usage>",
                    List.of(),
                    false));
        }
        Boolean security = bool(b, "SecurityEnabled");
        if (Boolean.FALSE.equals(security)) {
            ctx.findings.add(finding(
                    "SECURITY_DISABLED",
                    Category.SECURITY,
                    Severity.WARNING,
                    n,
                    "Security is disabled on " + n.nodeName(),
                    "Any client that reaches an acceptor can send, consume and create or delete queues, with no"
                            + " authentication and no authorisation.",
                    List.of(new Evidence(n.nodeName(), "SecurityEnabled", "false")),
                    "Enable security and define roles for each address.",
                    "<security-enabled>true</security-enabled>",
                    List.of(),
                    false));
        }
        acceptors(ctx, n, b);
        addressSettings(ctx, n);
    }

    private static void backupMissing(Context ctx, NodeRead n, HaPolicy policy) {
        if (!policy.expectsBackup() || !n.live() || n.artemisNodeId() == null) {
            return;
        }
        boolean partner = ctx.all.stream()
                .anyMatch(
                        o -> !o.nodeId().equals(n.nodeId()) && n.artemisNodeId().equals(o.artemisNodeId()));
        if (partner) {
            return;
        }
        ctx.findings.add(finding(
                "HA_BACKUP_MISSING",
                Category.HIGH_AVAILABILITY,
                Severity.WARNING,
                n,
                n.nodeName() + " is configured for failover, but no backup is paired with it",
                "Its HA policy expects a backup, and none shares its NodeID. If this node fails, nothing takes over"
                        + " its messages.",
                List.of(
                        new Evidence(n.nodeName(), "HAPolicy", policy.raw()),
                        new Evidence(n.nodeName(), "NodeID", n.artemisNodeId())),
                "Start the backup, or register it in Studio if it is running but unknown here.",
                null,
                List.of("Studio pairs endpoints by NodeID; a backup that has never synchronised with this primary"
                        + " does not share it yet."),
                false));
    }

    private static void policyMismatch(Context ctx, NodeRead n, HaPolicy policy) {
        if (n.artemisNodeId() == null || policy.family() == HaFamily.UNKNOWN) {
            return;
        }
        // Reported once per pair, on the endpoint that sorts first.
        List<NodeRead> pair = ctx.readable.stream()
                .filter(o -> n.artemisNodeId().equals(o.artemisNodeId()))
                .sorted(Comparator.comparing(o -> o.nodeId().toString()))
                .toList();
        if (pair.size() < 2 || !pair.get(0).nodeId().equals(n.nodeId())) {
            return;
        }
        Set<HaFamily> families = new LinkedHashSet<>();
        int primaries = 0;
        int backups = 0;
        List<Evidence> evidence = new ArrayList<>();
        for (NodeRead o : pair) {
            HaPolicy p = ctx.policy(o);
            if (p.family() != HaFamily.UNKNOWN) {
                families.add(p.family());
            }
            primaries += p.side() == HaSide.PRIMARY ? 1 : 0;
            backups += p.side() == HaSide.BACKUP ? 1 : 0;
            evidence.add(new Evidence(o.nodeName(), "HAPolicy", p.raw()));
        }
        String problem = families.size() > 1
                ? "The endpoints of one node use different HA policies"
                : primaries > 1
                        ? "Two endpoints of one node are both configured as primary"
                        : backups > 1 ? "Two endpoints of one node are both configured as backup" : null;
        if (problem == null) {
            return;
        }
        ctx.findings.add(finding(
                "HA_POLICY_MISMATCH",
                Category.HIGH_AVAILABILITY,
                Severity.CRITICAL,
                n,
                problem + " (NodeID " + n.artemisNodeId() + ")",
                "A primary and its backup must agree on how they coordinate. Mismatched halves either never"
                        + " replicate, or both decide they are live.",
                evidence,
                "Give the pair matching halves of one policy: <primary> on one and <backup> on the other, under the"
                        + " same <replication> or <shared-store> element.",
                """
                <!-- primary -->                     <!-- backup -->
                <ha-policy>                          <ha-policy>
                  <replication>                        <replication>
                    <primary/>                           <backup/>
                  </replication>                       </replication>
                </ha-policy>                         </ha-policy>""",
                List.of(),
                false));
    }

    private static void clustering(Context ctx, NodeRead n, JsonNode b) {
        if (ctx.logicalNodes() < 2) {
            return;
        }
        Boolean clustered = bool(b, "Clustered");
        JsonNode names = b.get("ClusterConnectionNames");
        boolean noConnection = names != null && names.isArray() && names.isEmpty();
        if (Boolean.FALSE.equals(clustered) || noConnection) {
            ctx.findings.add(finding(
                    "CLUSTER_NOT_CLUSTERED",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    n.nodeName() + " is not part of a cluster connection",
                    "Studio sees " + ctx.logicalNodes() + " nodes in this cluster, but this one neither load-balances"
                            + " to them nor redistributes to their consumers. Its messages stay where they land.",
                    List.of(
                            new Evidence(n.nodeName(), "Clustered", String.valueOf(clustered)),
                            new Evidence(
                                    n.nodeName(),
                                    "ClusterConnectionNames",
                                    names == null ? "(not reported)" : names.toString())),
                    "Add a cluster connection that names the other nodes.",
                    """
                    <cluster-connections>
                      <cluster-connection name="my-cluster">
                        <connector-ref>this-node</connector-ref>
                        <message-load-balancing>ON_DEMAND</message-load-balancing>
                        <max-hops>1</max-hops>
                        <static-connectors>
                          <connector-ref>other-node</connector-ref>
                        </static-connectors>
                      </cluster-connection>
                    </cluster-connections>""",
                    List.of(),
                    false));
        }

        if (n.clusterConnections() == null) {
            ctx.notAssessed.add(new NotAssessed("CLUSTER_*", n.subject(), n.clusterConnectionsError()));
            return;
        }
        List<Evidence> stopped = new ArrayList<>();
        List<Evidence> lbOff = new ArrayList<>();
        List<Evidence> hopsZero = new ArrayList<>();
        List<Evidence> noDup = new ArrayList<>();
        List<Evidence> stranded = new ArrayList<>();
        List<Evidence> unseen = new ArrayList<>();
        Long redistribution = ctx.redistributionDelay(n);
        for (var entry : n.clusterConnections().entrySet()) {
            String cc = entry.getKey();
            JsonNode a = entry.getValue();
            if (n.live() && Boolean.FALSE.equals(bool(a, "Started"))) {
                stopped.add(new Evidence(n.nodeName(), cc + " / Started", "false"));
            }
            String lb = text(a, "MessageLoadBalancingType");
            if ("OFF".equalsIgnoreCase(lb)) {
                lbOff.add(new Evidence(n.nodeName(), cc + " / MessageLoadBalancingType", lb));
            }
            JsonNode hops = a.get("MaxHops");
            if (hops != null && hops.isNumber() && hops.asInt() == 0) {
                hopsZero.add(new Evidence(n.nodeName(), cc + " / MaxHops", "0"));
            }
            if (Boolean.FALSE.equals(bool(a, "DuplicateDetection"))) {
                noDup.add(new Evidence(n.nodeName(), cc + " / DuplicateDetection", "false"));
            }
            if (n.live()
                    && lb != null
                    && (lb.equalsIgnoreCase("ON_DEMAND") || lb.equalsIgnoreCase("OFF_WITH_REDISTRIBUTION"))
                    && redistribution != null
                    && redistribution < 0) {
                stranded.add(new Evidence(n.nodeName(), cc + " / MessageLoadBalancingType", lb));
                stranded.add(new Evidence(
                        n.nodeName(), "address-setting # / redistributionDelay", redistribution.toString()));
            }
            JsonNode nodes = a.get("Nodes");
            if (n.live() && nodes != null && nodes.isObject()) {
                Set<String> seen = new TreeSet<>();
                nodes.properties().forEach(e -> seen.add(e.getKey()));
                for (NodeRead other : ctx.all) {
                    String id = other.artemisNodeId();
                    if (other.live() && id != null && !id.equals(n.artemisNodeId()) && !seen.contains(id)) {
                        unseen.add(new Evidence(
                                n.nodeName(), cc + " does not see", other.nodeName() + " (NodeID " + id + ")"));
                    }
                }
            }
        }
        if (!stopped.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_CONNECTION_STOPPED",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    "A cluster connection on " + n.nodeName() + " is stopped",
                    "A stopped cluster connection forwards nothing: this node neither load-balances nor"
                            + " redistributes, and the others cannot reach its consumers.",
                    stopped,
                    "Check the broker log for why it stopped, then start it.",
                    null,
                    List.of(),
                    false));
        }
        if (!lbOff.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_LOAD_BALANCING_OFF",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    "Message load balancing is off on " + n.nodeName(),
                    "Messages stay on the node they were sent to, even when only another node has consumers.",
                    lbOff,
                    "Balance on demand, so messages move to where the consumers are.",
                    "<message-load-balancing>ON_DEMAND</message-load-balancing>",
                    List.of(),
                    false));
        }
        if (!hopsZero.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_MAX_HOPS_ZERO",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    "The cluster connection on " + n.nodeName() + " forwards no hops",
                    "With max-hops 0 the node forms no bridges to its peers, so nothing is load-balanced or"
                            + " redistributed.",
                    hopsZero,
                    "Allow at least one hop for a fully connected cluster.",
                    "<max-hops>1</max-hops>",
                    List.of(),
                    false));
        }
        if (!noDup.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_NO_DUPLICATE_DETECTION",
                    Category.CLUSTERING,
                    Severity.INFO,
                    n,
                    "Cluster bridges on " + n.nodeName() + " do not detect duplicates",
                    "A message a bridge resends after a reconnection can arrive twice on the other node.",
                    noDup,
                    "Enable duplicate detection on the cluster connection.",
                    "<use-duplicate-detection>true</use-duplicate-detection>",
                    List.of(),
                    false));
        }
        if (!stranded.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_STRANDED_MESSAGES",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    "Messages can be stranded on " + n.nodeName() + ": redistribution is disabled",
                    "The cluster balances on demand, but the default redistribution-delay of -1 turns"
                            + " redistribution off. When a queue's last consumer on this node leaves, the messages"
                            + " already there stay there, even while consumers wait on another node.",
                    stranded,
                    "Set redistribution-delay to 0 (redistribute at once) or a few seconds, for # or the addresses"
                            + " that need it. Broker configuration can apply this over the management API.",
                    REDISTRIBUTION_SNIPPET,
                    List.of(),
                    true));
        }
        if (!unseen.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_MEMBERSHIP_INCOMPLETE",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    n.nodeName() + "'s cluster connection does not see every live node",
                    "Studio sees these nodes live, but this node has not formed a cluster bridge to them. Messages"
                            + " are neither load-balanced nor redistributed between them, so the cluster behaves as"
                            + " two.",
                    unseen,
                    "Check the connectors each node advertises and the cluster connection's static connectors or"
                            + " discovery group. UDP discovery rarely works in containers or clouds; list the peers"
                            + " statically instead.",
                    """
                    <cluster-connection name="my-cluster">
                      <connector-ref>this-node</connector-ref>
                      <static-connectors>
                        <connector-ref>node-b</connector-ref>
                        <connector-ref>node-c</connector-ref>
                      </static-connectors>
                    </cluster-connection>""",
                    List.of("A node that has just started may not have joined yet; a finding that persists across"
                            + " reviews is not a start-up race."),
                    false));
        }
    }

    /** A connector a clustered node advertises to its peers must name a host they can reach. */
    private static void connectors(Context ctx, NodeRead n, JsonNode b) {
        if (!Boolean.TRUE.equals(bool(b, "Clustered"))) {
            return;
        }
        JsonNode connectors = ctx.embedded(b.get("ConnectorsAsJSON"));
        if (connectors == null || !connectors.isArray()) {
            return;
        }
        List<Evidence> loopback = new ArrayList<>();
        for (JsonNode c : connectors) {
            if (isInVm(c)) {
                continue;
            }
            String host = text(c.path("params"), "host");
            if (host == null || isLoopbackOrWildcard(host)) {
                loopback.add(new Evidence(
                        n.nodeName(),
                        "connector " + text(c, "name") + " / host",
                        host == null ? "(not set — defaults to localhost)" : host));
            }
        }
        if (!loopback.isEmpty()) {
            ctx.findings.add(finding(
                    "CLUSTER_LOOPBACK_CONNECTOR",
                    Category.CLUSTERING,
                    Severity.WARNING,
                    n,
                    n.nodeName() + " advertises a connector its peers cannot reach",
                    "Connectors are what a node tells the cluster and its clients to connect to. A loopback or"
                            + " wildcard host sends every peer to itself, so cluster bridges and client failover to"
                            + " this node fail.",
                    loopback,
                    "Give each connector the host name or address other nodes and clients use to reach this node.",
                    "<connector name=\"this-node\">tcp://broker-1.example.internal:61616</connector>",
                    List.of("A connector used only by local tools may be loopback on purpose; it matters when a cluster"
                            + " connection or a client failover list uses it."),
                    false));
        }
    }

    private static void acceptors(Context ctx, NodeRead n, JsonNode b) {
        JsonNode acceptors = ctx.embedded(b.get("AcceptorsAsJSON"));
        if (acceptors == null || !acceptors.isArray()) {
            return;
        }
        List<Evidence> plain = new ArrayList<>();
        for (JsonNode a : acceptors) {
            if (isInVm(a)) {
                continue;
            }
            String ssl = text(a.path("params"), "sslEnabled");
            if (!"true".equalsIgnoreCase(ssl)) {
                plain.add(new Evidence(
                        n.nodeName(),
                        "acceptor " + text(a, "name") + " / sslEnabled",
                        ssl == null ? "(not set)" : ssl));
            }
        }
        if (!plain.isEmpty()) {
            ctx.findings.add(finding(
                    "SECURITY_PLAINTEXT_ACCEPTOR",
                    Category.SECURITY,
                    Severity.INFO,
                    n,
                    n.nodeName() + " accepts connections without TLS",
                    "Credentials and message bodies cross the network in clear on these acceptors.",
                    plain,
                    "Enable TLS on acceptors reachable from outside a trusted network.",
                    "<acceptor name=\"artemis\">tcp://0.0.0.0:61617?sslEnabled=true;keyStorePath=/etc/artemis/broker.p12;keyStorePassword=ENC(...)</acceptor>",
                    List.of("Acceptors bound to a private network, or behind a TLS-terminating proxy, may be plaintext"
                            + " by design."),
                    false));
        }
    }

    /** Absent keys are the Artemis defaults: {@code toJSON} omits unset values. */
    private static void addressSettings(Context ctx, NodeRead n) {
        JsonNode s = n.defaultAddressSettings();
        if (s == null) {
            ctx.notAssessed.add(new NotAssessed("MESSAGES_*", n.subject(), n.addressSettingsError()));
            return;
        }
        String dla = text(s, "deadLetterAddress");
        int attempts = s.path("maxDeliveryAttempts").asInt(10);
        String expiry = text(s, "expiryAddress");
        String full = text(s, "addressFullMessagePolicy");
        if (dla == null && attempts != -1) {
            ctx.findings.add(finding(
                    "MESSAGES_NO_DLA",
                    Category.MESSAGE_SAFETY,
                    Severity.WARNING,
                    n,
                    "No dead-letter address on " + n.nodeName() + ": undeliverable messages are dropped",
                    "A message that fails delivery " + attempts + " times is removed with only a log line, because"
                            + " # names no dead-letter address to move it to.",
                    List.of(
                            new Evidence(n.nodeName(), "address-setting # / deadLetterAddress", "(not set)"),
                            new Evidence(
                                    n.nodeName(),
                                    "address-setting # / maxDeliveryAttempts",
                                    Integer.toString(attempts))),
                    "Name a dead-letter address, so a poison message is kept for inspection instead of lost.",
                    """
                    <address-setting match="#">
                      <dead-letter-address>DLQ</dead-letter-address>
                      <auto-create-dead-letter-resources>true</auto-create-dead-letter-resources>
                    </address-setting>""",
                    List.of(),
                    true));
        }
        if (attempts == -1) {
            ctx.findings.add(finding(
                    "MESSAGES_INFINITE_REDELIVERY",
                    Category.MESSAGE_SAFETY,
                    Severity.WARNING,
                    n,
                    "Redelivery is unlimited on " + n.nodeName(),
                    "A message that always fails is redelivered forever, blocking its queue for every consumer"
                            + " behind it.",
                    List.of(new Evidence(n.nodeName(), "address-setting # / maxDeliveryAttempts", "-1")),
                    "Cap delivery attempts and send what exceeds them to a dead-letter address.",
                    """
                    <address-setting match="#">
                      <max-delivery-attempts>10</max-delivery-attempts>
                      <dead-letter-address>DLQ</dead-letter-address>
                    </address-setting>""",
                    List.of(),
                    true));
        }
        if (expiry == null) {
            ctx.findings.add(finding(
                    "MESSAGES_NO_EXPIRY_ADDRESS",
                    Category.MESSAGE_SAFETY,
                    Severity.INFO,
                    n,
                    "No expiry address on " + n.nodeName() + ": expired messages are discarded",
                    "A message whose time-to-live passes is deleted without a trace.",
                    List.of(new Evidence(n.nodeName(), "address-setting # / expiryAddress", "(not set)")),
                    "Name an expiry address if expired messages need to be audited or replayed.",
                    """
                    <address-setting match="#">
                      <expiry-address>ExpiryQueue</expiry-address>
                    </address-setting>""",
                    List.of(),
                    true));
        }
        if ("DROP".equalsIgnoreCase(full)) {
            ctx.findings.add(finding(
                    "MESSAGES_DROP_WHEN_FULL",
                    Category.MESSAGE_SAFETY,
                    Severity.WARNING,
                    n,
                    "Full addresses silently drop messages on " + n.nodeName(),
                    "Once an address reaches its size limit, new messages are discarded and the producer is not"
                            + " told.",
                    List.of(new Evidence(n.nodeName(), "address-setting # / addressFullMessagePolicy", full)),
                    "Page to disk, or block the producer, instead of dropping.",
                    """
                    <address-setting match="#">
                      <address-full-policy>PAGE</address-full-policy>
                    </address-setting>""",
                    List.of(),
                    true));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Finding finding(
            String code,
            Category category,
            Severity severity,
            NodeRead n,
            String title,
            String impact,
            List<Evidence> evidence,
            String recommendation,
            String snippet,
            List<String> caveats,
            boolean appliable) {
        return new Finding(
                code,
                category,
                severity,
                n.subject(),
                title,
                impact,
                evidence,
                recommendation,
                snippet,
                caveats,
                appliable);
    }

    private static boolean isInVm(JsonNode transport) {
        String factory = text(transport, "factoryClassName");
        return factory != null && factory.contains("InVM");
    }

    static boolean isLoopbackOrWildcard(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("localhost")
                || h.startsWith("127.")
                || h.equals("0.0.0.0")
                || h.equals("::1")
                || h.equals("[::1]")
                || h.equals("::")
                || h.equals("[::]");
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }

    static Boolean bool(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        String s = v.asString();
        return "true".equalsIgnoreCase(s) ? Boolean.TRUE : "false".equalsIgnoreCase(s) ? Boolean.FALSE : null;
    }

    /** Everything the rules share about one review. */
    private static final class Context {
        final List<NodeRead> all;
        final List<NodeRead> readable;
        final ObjectMapper mapper;
        final Set<String> studioNodeIds = new TreeSet<>();
        final Map<java.util.UUID, HaPolicy> policies = new LinkedHashMap<>();
        final List<Finding> findings = new ArrayList<>();
        final List<NotAssessed> notAssessed = new ArrayList<>();
        final Set<String> evaluated = new LinkedHashSet<>();

        Context(List<NodeRead> nodes, ObjectMapper mapper) {
            this.all = List.copyOf(nodes);
            this.readable = nodes.stream().filter(NodeRead::readable).toList();
            this.mapper = mapper;
            nodes.stream().map(NodeRead::artemisNodeId).filter(Objects::nonNull).forEach(studioNodeIds::add);
        }

        /** Logical nodes: a primary and its backup share a NodeID and are one. */
        int logicalNodes() {
            return Math.max(studioNodeIds.size(), 1);
        }

        HaPolicy policy(NodeRead n) {
            return policies.computeIfAbsent(n.nodeId(), id -> haPolicy(text(n.broker(), "HAPolicy")));
        }

        /** The NodeIDs this node's cluster connections are bridged to, plus its own. */
        Set<String> clusterMembers(NodeRead n) {
            Set<String> members = new TreeSet<>();
            if (n.artemisNodeId() != null) {
                members.add(n.artemisNodeId());
            }
            if (n.clusterConnections() != null) {
                for (JsonNode cc : n.clusterConnections().values()) {
                    JsonNode nodes = cc.get("Nodes");
                    if (nodes != null && nodes.isObject()) {
                        nodes.properties().forEach(e -> members.add(e.getKey()));
                    }
                }
            }
            return members;
        }

        /** {@code #}'s redistributionDelay; absent means the default, -1. Null when the settings were not read. */
        Long redistributionDelay(NodeRead n) {
            JsonNode s = n.defaultAddressSettings();
            return s == null ? null : s.path("redistributionDelay").asLong(-1);
        }

        /** {@code ConnectorsAsJSON}/{@code AcceptorsAsJSON} are JSON inside a string attribute. */
        JsonNode embedded(JsonNode value) {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isArray()) {
                return value;
            }
            try {
                return mapper.readTree(value.asString());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
