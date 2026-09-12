package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.AddressMatch;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigValidator;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerXmlCodec;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Violation;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigDeclarationEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigDeclarationRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigRevisionEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigRevisionRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A cluster's declared configuration: read, save as a new revision, configure how
 * it is applied, import from and export to {@code broker.xml}, and adopt from what
 * the cluster is running (ADR-0067 D1, D2, D12). Nothing here contacts a broker to
 * write; adoption reads.
 */
@Service
@RequiredArgsConstructor
public class BrokerConfigService {

    static final String AUDIT_EDIT = "EDIT_BROKER_CONFIG";
    static final String AUDIT_CONFIGURE = "CONFIGURE_BROKER_CONFIG";

    public enum Source {
        EDIT,
        IMPORT_XML,
        ADOPT,
        MCP,
        /** Derived from the capability probe's recommendations and saved on the operator's word (ADR-0068). */
        RECOMMENDED
    }

    private final BrokerConfigDeclarationRepository declarations;
    private final BrokerConfigRevisionRepository revisions;
    private final BrokerConfigNodeStateRepository nodeStates;
    private final ClusterRepository clusters;
    private final QueueSnapshotRepository queueSnapshots;
    private final BrokerConfigReads reads;
    private final BrokerConfigOperations ops;
    private final ClusterLock lock;
    private final ClusterAccessGuard clusterAccess;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ObjectMapper mapper;

    /** What the API returns for a cluster's declaration. */
    public record Declaration(
            UUID clusterId,
            String clusterName,
            boolean declared,
            int revision,
            long revisionId,
            BrokerConfigDocument document,
            ApplyMode applyMode,
            boolean reportUndeclared,
            List<String> undeclaredExclusions,
            Instant updatedAt,
            String updatedBy,
            Source source,
            String note,
            List<NodeState> nodes) {}

    /** The latest evaluation of one node, as stored. */
    public record NodeState(
            UUID nodeId,
            String nodeName,
            boolean live,
            BrokerConfigNodeStateEntity.State state,
            String detail,
            Integer verifiedRevision,
            Instant evaluatedAt,
            List<BrokerConfigDriftService.DriftFinding> findings,
            BrokerConfigNodeStateEntity.Basis basis,
            Long basisRef) {}

    public record Revision(
            int revision,
            long id,
            Instant createdAt,
            String createdBy,
            Source source,
            String note,
            BrokerConfigDocument document) {}

    /**
     * What adoption produced, for review before it is saved.
     *
     * <p>{@code closes} is the part an operator must see before confirming: adoption
     * declares what the cluster is already running, so every open drift finding
     * disappears on the next evaluation — not because a broker was written, but
     * because the declaration moved to meet it. A preview that shows only the
     * document reads exactly like a successful apply.
     */
    public record Adoption(
            BrokerConfigDocument document,
            List<String> notes,
            List<String> disagreements,
            List<ClosedFinding> closes) {}

    /** One drift finding an adoption would erase, and the node that reported it. */
    public record ClosedFinding(UUID nodeId, String nodeName, BrokerConfigDriftService.DriftFinding finding) {}

    // ---- read ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Declaration get(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        ClusterEntity cluster =
                clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        Optional<BrokerConfigDeclarationEntity> header = declarations.findById(clusterId);
        List<NodeState> nodes = nodeStates(clusterId);
        if (header.isEmpty()) {
            return new Declaration(
                    clusterId,
                    cluster.getName(),
                    false,
                    0,
                    0,
                    BrokerConfigDocument.empty(),
                    ApplyMode.STUDIO_MANAGED,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    nodes);
        }
        BrokerConfigDeclarationEntity h = header.get();
        BrokerConfigRevisionEntity current = revisions
                .findById(h.getCurrentRevisionId())
                .orElseThrow(() -> new IllegalStateException("Declaration points at a missing revision"));
        return new Declaration(
                clusterId,
                cluster.getName(),
                true,
                current.getRevision(),
                current.getId(),
                parse(current.getDocument()),
                h.mode(),
                h.isReportUndeclared(),
                exclusions(h),
                current.getCreatedAt(),
                current.getCreatedBy(),
                Source.valueOf(current.getSource()),
                current.getNote(),
                nodes);
    }

    @Transactional(readOnly = true)
    public List<Revision> revisions(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return revisions.findByClusterIdOrderByRevisionDesc(clusterId).stream()
                .map(this::revision)
                .toList();
    }

    @Transactional(readOnly = true)
    public Revision revision(UUID clusterId, int number) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return revisions
                .findByClusterIdAndRevision(clusterId, number)
                .map(this::revision)
                .orElseThrow(() -> new NotFoundException("revision", number));
    }

    /** The current document, or the empty one for an undeclared cluster. Internal callers only. */
    @Transactional(readOnly = true)
    public Optional<CurrentRevision> current(UUID clusterId) {
        return declarations
                .findById(clusterId)
                .flatMap(h -> revisions
                        .findById(h.getCurrentRevisionId())
                        .map(r -> new CurrentRevision(
                                r.getId(), r.getRevision(), parse(r.getDocument()), h, Source.valueOf(r.getSource()))));
    }

    public record CurrentRevision(
            long id,
            int revision,
            BrokerConfigDocument document,
            BrokerConfigDeclarationEntity header,
            /** How this revision came to exist; drift reads it to explain an IN_SYNC node. */
            Source source) {}

    // ---- write -----------------------------------------------------------

    /**
     * Save a new revision. {@code expectedRevision} is the revision the operator
     * edited; when it is no longer current the save is refused (D12), because
     * silently overwriting another operator's revision is how a cluster ends up
     * declared as something nobody intended.
     */
    @Transactional
    public Declaration save(
            UUID clusterId, BrokerConfigDocument document, Integer expectedRevision, String note, Source source) {
        return save(clusterId, document, expectedRevision, note, source, null);
    }

    /**
     * As above, with the typed confirmation an adoption needs.
     *
     * <p>An adoption that would close open drift findings is refused without the
     * cluster's name, because it is indistinguishable in its effect from an apply
     * and opposite in its meaning: the broker keeps whatever it is doing. It is also
     * refused while an apply holds the cluster, so that a run in flight cannot have
     * the declaration it is applying moved underneath it.
     */
    @Transactional
    public Declaration save(
            UUID clusterId,
            BrokerConfigDocument document,
            Integer expectedRevision,
            String note,
            Source source,
            String confirm) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_WRITE);
        List<Violation> violations = BrokerConfigValidator.validate(document);
        if (!violations.isEmpty()) {
            throw new BrokerConfigInvalidException(violations);
        }
        if (source == Source.ADOPT) {
            List<ClosedFinding> closes = openFindings(clusterId);
            if (!closes.isEmpty() && !clusterName(clusterId).equals(confirm)) {
                throw new ConflictException(
                        "adoption-unconfirmed",
                        "Adopting this document closes " + closes.size() + " open drift finding(s) without writing"
                                + " to any broker. Confirm with the cluster's name to record that as intended.");
            }
            if (lock.isHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY)) {
                throw new ConflictException(
                        "apply-in-progress",
                        "A configuration apply is running on this cluster. Adopting now would move the"
                                + " declaration it is applying. Wait for it to finish.");
            }
        }
        Optional<BrokerConfigDeclarationEntity> header = declarations.findById(clusterId);
        int currentNumber = header.map(h -> revisions
                        .findById(h.getCurrentRevisionId())
                        .map(BrokerConfigRevisionEntity::getRevision)
                        .orElse(0))
                .orElse(0);
        if (expectedRevision != null && expectedRevision != currentNumber) {
            throw new ConflictException(
                    "stale-revision",
                    "Revision " + currentNumber + " was saved while you were editing revision " + expectedRevision
                            + ". Reload it and re-apply your edit.");
        }
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                AUDIT_EDIT,
                "cluster",
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("revision", currentNumber + 1, "source", source.name()),
                false);
        BrokerConfigRevisionEntity saved = revisions.save(new BrokerConfigRevisionEntity(
                clusterId,
                currentNumber + 1,
                mapper.writeValueAsString(document),
                source.name(),
                note == null || note.isBlank() ? null : note.trim(),
                actorResolver.resolve().displayName()));
        if (header.isPresent()) {
            header.get().pointAt(saved.getId());
            declarations.save(header.get());
        } else {
            declarations.save(new BrokerConfigDeclarationEntity(clusterId, saved.getId()));
        }
        audit.succeed(event, 1);
        return get(clusterId);
    }

    /** Change how the cluster's configuration is applied and what drift reports. */
    @Transactional
    public Declaration configure(
            UUID clusterId, ApplyMode mode, boolean reportUndeclared, List<String> undeclaredExclusions) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_WRITE);
        BrokerConfigDeclarationEntity header = declarations
                .findById(clusterId)
                .orElseThrow(() -> new ConflictException(
                        "no-declaration",
                        "Declare the cluster's configuration first; the mode belongs to a declaration."));
        List<String> exclusions = undeclaredExclusions == null
                ? List.of()
                : undeclaredExclusions.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                AUDIT_CONFIGURE,
                "cluster",
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("applyMode", mode.name(), "reportUndeclared", reportUndeclared, "exclusions", exclusions),
                false);
        header.configure(mode, reportUndeclared, mapper.writeValueAsString(exclusions));
        declarations.save(header);
        audit.succeed(event, 1);
        return get(clusterId);
    }

    // ---- XML -------------------------------------------------------------

    /** Parse pasted XML for preview. Pure: nothing is saved. */
    public BrokerXmlCodec.ParseResult importXml(UUID clusterId, String xml) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_WRITE);
        return BrokerXmlCodec.parse(xml);
    }

    @Transactional(readOnly = true)
    public String exportXml(UUID clusterId, Integer revisionNumber) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        BrokerConfigDocument doc = revisionNumber == null
                ? get(clusterId).document()
                : revision(clusterId, revisionNumber).document();
        return BrokerXmlCodec.write(doc);
    }

    // ---- adopt -----------------------------------------------------------

    /**
     * Build a declaration from what the live nodes are running, for review. The
     * catch-all gets every key the broker reports; each address gets the keys it
     * resolves differently from the catch-all, keyed by the address, because the
     * broker cannot report the match pattern that produced them (§15 M5). Where
     * nodes disagree, the disagreement is listed and the first live node's value is
     * kept.
     */
    @Transactional(readOnly = true)
    public Adoption adopt(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_WRITE);
        List<String> notes = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();
        notes.add("A broker reports the settings an address resolves to, not the match patterns its"
                + " configuration declares. Address settings and security settings are therefore keyed by"
                + " '#' and by address; merge them into the patterns you know before applying.");

        List<ObservedNodeConfig> observed = new ArrayList<>();
        for (BrokerNodeEntity node : reads.targets(clusterId)) {
            if (!Boolean.TRUE.equals(node.getActive())) {
                continue;
            }
            try {
                observed.add(ops.readForAdoption(reads.client(clusterId, node), node.getId(), node.getName()));
            } catch (RuntimeException e) {
                notes.add(node.getName() + " could not be read and contributed nothing: " + e.getMessage());
            }
        }
        if (observed.isEmpty()) {
            throw new ConflictException("no-live-node", "No live node could be read, so there is nothing to adopt.");
        }
        ObservedNodeConfig first = observed.getFirst();

        // Addresses and queues: the union across nodes; queues from the snapshot cache.
        Map<String, Set<String>> addresses = new TreeMap<>();
        for (ObservedNodeConfig n : observed) {
            n.addresses()
                    .forEach((a, types) -> addresses.merge(a, new TreeSet<>(types), (x, y) -> {
                        x.addAll(y);
                        return x;
                    }));
        }
        Map<String, List<QueueDecl>> queuesByAddress = new TreeMap<>();
        Map<String, QueueSnapshotEntity> seen = new LinkedHashMap<>();
        for (QueueSnapshotEntity q : queueSnapshots.findByClusterId(clusterId)) {
            if (q.getAddress() == null
                    || q.getAddress().startsWith("activemq.")
                    || q.getAddress().startsWith("$sys.")) {
                continue;
            }
            seen.putIfAbsent(q.getQueueName(), q);
        }
        for (QueueSnapshotEntity q : seen.values()) {
            addresses
                    .computeIfAbsent(q.getAddress(), k -> new TreeSet<>())
                    .add(q.getRoutingType().toUpperCase());
            queuesByAddress
                    .computeIfAbsent(q.getAddress(), k -> new ArrayList<>())
                    .add(new QueueDecl(
                            q.getQueueName(),
                            q.getRoutingType().toUpperCase(),
                            null,
                            q.isDurable(),
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        List<AddressDecl> addressDecls = new ArrayList<>();
        addresses.forEach((name, types) -> {
            if (types.isEmpty()) {
                types.add("ANYCAST");
            }
            addressDecls.add(new AddressDecl(name, types, queuesByAddress.getOrDefault(name, List.of())));
        });

        // Address settings: '#' in full, then per-address differences.
        List<AddressSettingDecl> settingDecls = new ArrayList<>();
        Map<String, Object> base = first.addressSettings().getOrDefault("#", Map.of());
        settingDecls.add(new AddressSettingDecl("#", base));
        for (String address : addresses.keySet()) {
            Map<String, Object> resolved = first.addressSettings().get(address);
            if (resolved == null) {
                continue;
            }
            Map<String, Object> diff = new TreeMap<>();
            resolved.forEach((k, v) -> {
                if (!sameValue(v, base.get(k))) {
                    diff.put(k, v);
                }
            });
            if (!diff.isEmpty()) {
                settingDecls.add(new AddressSettingDecl(address, diff));
            }
            for (ObservedNodeConfig other : observed.subList(1, observed.size())) {
                Map<String, Object> theirs = other.addressSettings().get(address);
                if (theirs != null && !theirs.equals(resolved)) {
                    disagreements.add("Address settings for " + address + " differ between " + first.nodeName()
                            + " and " + other.nodeName() + "; " + first.nodeName() + "'s were kept.");
                }
            }
        }

        // Security settings: '#' and per-address role sets that differ from it.
        List<SecuritySettingDecl> securityDecls = new ArrayList<>();
        Map<PermissionType, Set<String>> baseRoles = first.securitySettings().getOrDefault("#", Map.of());
        if (!baseRoles.isEmpty()) {
            securityDecls.add(new SecuritySettingDecl("#", baseRoles));
        }
        for (String address : addresses.keySet()) {
            Map<PermissionType, Set<String>> roles = first.securitySettings().get(address);
            if (roles == null || roles.isEmpty() || sameRoles(roles, baseRoles)) {
                continue;
            }
            if (!AddressMatch.isCatchAll(address)) {
                securityDecls.add(new SecuritySettingDecl(address, new EnumMap<>(roles)));
            }
        }

        // Diverts: union by name, first node's properties kept.
        Map<String, DivertDecl> diverts = new TreeMap<>();
        for (ObservedNodeConfig n : observed) {
            n.diverts().forEach((name, d) -> {
                DivertDecl kept = diverts.putIfAbsent(name, d);
                if (kept != null && !kept.sameAs(d)) {
                    disagreements.add("Divert " + name + " differs between " + first.nodeName() + " and " + n.nodeName()
                            + "; " + first.nodeName() + "'s was kept.");
                }
            });
        }
        for (ObservedNodeConfig n : observed.subList(1, observed.size())) {
            for (String name : diverts.keySet()) {
                if (!n.diverts().containsKey(name)) {
                    disagreements.add("Divert " + name + " is missing on " + n.nodeName() + ".");
                }
            }
        }

        BrokerConfigDocument doc = new BrokerConfigDocument(
                BrokerConfigDocument.CURRENT_VERSION,
                addressDecls,
                settingDecls,
                securityDecls,
                new ArrayList<>(diverts.values()));
        List<ClosedFinding> closes = openFindings(clusterId);
        if (!closes.isEmpty()) {
            notes.add(closes.size() + " open drift finding(s) will be closed by adopting this document, and no"
                    + " broker will be written: the declaration moves to match the cluster. Apply the current"
                    + " declaration instead if the cluster is what is wrong.");
        }
        return new Adoption(doc, notes, disagreements, closes);
    }

    /** Every drift finding currently recorded against the cluster, node by node. */
    private List<ClosedFinding> openFindings(UUID clusterId) {
        List<ClosedFinding> out = new ArrayList<>();
        for (NodeState node : nodeStates(clusterId)) {
            if (node.state() != BrokerConfigNodeStateEntity.State.DRIFTED) {
                continue;
            }
            node.findings().forEach(f -> out.add(new ClosedFinding(node.nodeId(), node.nodeName(), f)));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------

    BrokerConfigDocument parse(String json) {
        return mapper.readValue(json, BrokerConfigDocument.class);
    }

    private Revision revision(BrokerConfigRevisionEntity r) {
        return new Revision(
                r.getRevision(),
                r.getId(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                Source.valueOf(r.getSource()),
                r.getNote(),
                parse(r.getDocument()));
    }

    List<String> exclusions(BrokerConfigDeclarationEntity h) {
        return mapper.readValue(h.getUndeclaredExclusions(), new TypeReference<List<String>>() {});
    }

    private List<NodeState> nodeStates(UUID clusterId) {
        Map<UUID, BrokerConfigNodeStateEntity> stored = new LinkedHashMap<>();
        nodeStates.findByClusterId(clusterId).forEach(s -> stored.put(s.getNodeId(), s));
        List<NodeState> out = new ArrayList<>();
        for (BrokerNodeEntity node : reads.targets(clusterId)) {
            BrokerConfigNodeStateEntity s = stored.get(node.getId());
            boolean live = Boolean.TRUE.equals(node.getActive());
            if (s == null) {
                out.add(new NodeState(
                        node.getId(),
                        node.getName(),
                        live,
                        BrokerConfigNodeStateEntity.State.NOT_EVALUATED,
                        "Not evaluated yet.",
                        null,
                        null,
                        List.of(),
                        null,
                        null));
            } else {
                out.add(new NodeState(
                        node.getId(),
                        node.getName(),
                        live,
                        s.state(),
                        s.getDetail(),
                        s.getVerifiedRevision(),
                        s.getEvaluatedAt(),
                        mapper.readValue(
                                s.getFindings(), new TypeReference<List<BrokerConfigDriftService.DriftFinding>>() {}),
                        s.basis(),
                        s.getBasisRef()));
            }
        }
        return out;
    }

    String clusterName(UUID clusterId) {
        return clusters.findById(clusterId).map(ClusterEntity::getName).orElse(clusterId.toString());
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) == 0;
        }
        return a.toString().equalsIgnoreCase(b.toString());
    }

    private static boolean sameRoles(Map<PermissionType, Set<String>> a, Map<PermissionType, Set<String>> b) {
        for (PermissionType t : PermissionType.values()) {
            if (!a.getOrDefault(t, Set.of()).equals(b.getOrDefault(t, Set.of()))) {
                return false;
            }
        }
        return true;
    }
}
