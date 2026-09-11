package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigPlanner;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.OwnedItem;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Finding;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.NodePlan;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Step;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PlanOptions;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigDeclarationRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares every live node against the cluster's current revision and records the
 * result per node (ADR-0067 D8). Evaluation is scheduled and on demand; nothing
 * that follows from it ever is. The findings are the planner's own steps read as
 * facts: an add that nobody applies is a missing resource, a replace is a divergent
 * one. Read-only, unaudited.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerConfigDriftService {

    public static final String SSE_TOPIC = "config";

    private final BrokerConfigService configs;
    private final BrokerConfigReads reads;
    private final BrokerConfigDeclarationRepository declarations;
    private final BrokerConfigNodeStateRepository nodeStates;
    private final BrokerConfigOwnedItemRepository ownedItems;
    private final ClusterAccessGuard clusterAccess;
    private final ClusterLock lock;
    private final SseHub sseHub;
    private final ObjectMapper mapper;

    /** One thing a node does differently from the declaration, in the words the screen shows. */
    public record DriftFinding(
            FindingKind kind,
            Section section,
            String key,
            String detail,
            Map<String, Object> declared,
            Map<String, Object> observed) {}

    /** A whole evaluation, per node. */
    public record Report(int revision, Instant evaluatedAt, List<NodeReport> nodes) {}

    public record NodeReport(
            UUID nodeId, String nodeName, boolean live, State state, String detail, List<DriftFinding> findings) {}

    /** Evaluate now, on an operator's request. */
    @Transactional
    public Report evaluate(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return evaluateInternal(clusterId);
    }

    /** The scheduled pass: every declared cluster, under the cluster lock, never throwing. */
    public void evaluateAll() {
        for (var header : declarations.findAll()) {
            UUID clusterId = header.getClusterId();
            try {
                lock.runIfHeld(clusterId, () -> evaluateInternal(clusterId));
            } catch (RuntimeException e) {
                log.warn("Drift evaluation for cluster {} failed: {}", clusterId, e.getMessage());
            }
        }
    }

    /**
     * Re-evaluate right after an apply so the drift tab reflects what was just written.
     *
     * <p>Called from the apply's {@code afterCommit} hook, where the committed
     * transaction's resources are still bound and any write would join it and be
     * discarded (Spring's documented {@code afterCommit} contract). A new transaction
     * is therefore required, not merely preferred: without it the node states written
     * here never reach the database and the tab keeps saying "not evaluated".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateAfterApply(UUID clusterId) {
        try {
            evaluateInternal(clusterId);
        } catch (RuntimeException e) {
            log.warn("Post-apply drift evaluation for cluster {} failed: {}", clusterId, e.getMessage());
        }
    }

    Report evaluateInternal(UUID clusterId) {
        var current = configs.current(clusterId);
        if (current.isEmpty()) {
            return new Report(0, Instant.now(), List.of());
        }
        var revision = current.get();
        Set<OwnedItem> owned = owned(clusterId);
        ReadScope scope = ReadScope.of(
                revision.document(),
                ownedKeys(owned, Section.ADDRESS_SETTING),
                ownedKeys(owned, Section.SECURITY_SETTING));
        List<ObservedNodeConfig> observed = reads.observe(clusterId, scope);
        PlanOptions options =
                PlanOptions.drift(revision.header().isReportUndeclared(), configs.exclusions(revision.header()));
        Plan plan = BrokerConfigPlanner.plan(revision.document(), observed, owned, options);

        List<NodeReport> reports = new ArrayList<>();
        Instant now = Instant.now();
        for (ObservedNodeConfig node : observed) {
            NodeReport report = report(node, plan, revision.revision());
            BrokerConfigNodeStateEntity row = nodeStates
                    .findById(key(clusterId, node.nodeId()))
                    .orElseGet(() -> new BrokerConfigNodeStateEntity(clusterId, node.nodeId()));
            row.record(
                    report.state(),
                    report.detail(),
                    report.state() == State.IN_SYNC || report.state() == State.DRIFTED ? revision.revision() : null,
                    mapper.writeValueAsString(report.findings()));
            nodeStates.save(row);
            reports.add(report);
        }
        publishAfterCommit(clusterId);
        return new Report(revision.revision(), now, reports);
    }

    /** The screen refetches on this topic; it must not do so before the rows it will read are committed. */
    private void publishAfterCommit(UUID clusterId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseHub.publish(clusterId, SSE_TOPIC);
                }
            });
        } else {
            sseHub.publish(clusterId, SSE_TOPIC);
        }
    }

    /** Read the planner's answer for one node as drift. */
    static NodeReport report(ObservedNodeConfig node, Plan plan, int revision) {
        if (!plan.valid()) {
            return new NodeReport(
                    node.nodeId(),
                    node.nodeName(),
                    node.live(),
                    State.NOT_EVALUATED,
                    "The declaration cannot be compared: "
                            + plan.violations().getFirst().message(),
                    List.of());
        }
        if (!node.live()) {
            return new NodeReport(
                    node.nodeId(),
                    node.nodeName(),
                    false,
                    State.NOT_EVALUATED,
                    "Not live. A backup inherits what its primary holds once it becomes active.",
                    List.of());
        }
        if (node.unavailableReason() != null) {
            return new NodeReport(
                    node.nodeId(), node.nodeName(), true, State.UNREACHABLE, node.unavailableReason(), List.of());
        }
        List<DriftFinding> findings = new ArrayList<>();
        NodePlan mine = plan.nodes().stream()
                .filter(n -> n.nodeId().equals(node.nodeId()))
                .findFirst()
                .orElse(null);
        if (mine != null) {
            for (Step s : mine.steps()) {
                if (s.already()) {
                    continue;
                }
                if (s.op() == Op.ADD) {
                    findings.add(new DriftFinding(
                            FindingKind.MISSING, s.section(), s.key(), s.description(), s.after(), Map.of()));
                } else if (s.op() == Op.REPLACE) {
                    findings.add(new DriftFinding(
                            FindingKind.DIVERGENT, s.section(), s.key(), s.description(), s.after(), s.before()));
                } else {
                    findings.add(new DriftFinding(
                            FindingKind.UNDECLARED,
                            s.section(),
                            s.key(),
                            "Studio applied it and it is no longer declared.",
                            Map.of(),
                            s.before()));
                }
            }
        }
        for (Finding f : plan.findings()) {
            if (node.nodeId().equals(f.nodeId())) {
                findings.add(new DriftFinding(f.kind(), f.section(), f.key(), f.detail(), Map.of(), Map.of()));
            }
        }
        // Two REMOVE steps for one divert replace collapse into the DIVERGENT they mean.
        List<DriftFinding> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DriftFinding f : findings) {
            if (seen.add(f.kind() + ":" + f.section() + ":" + f.key())) {
                deduped.add(f);
            }
        }
        State state = deduped.isEmpty() ? State.IN_SYNC : State.DRIFTED;
        String detail = state == State.IN_SYNC ? "Matches revision " + revision + "." : deduped.size() + " findings.";
        return new NodeReport(node.nodeId(), node.nodeName(), true, state, detail, deduped);
    }

    Set<OwnedItem> owned(UUID clusterId) {
        Set<OwnedItem> out = new HashSet<>();
        for (BrokerConfigOwnedItemEntity e : ownedItems.findByClusterId(clusterId)) {
            out.add(new OwnedItem(Section.valueOf(e.getKind()), e.getItemKey()));
        }
        return out;
    }

    static Set<String> ownedKeys(Set<OwnedItem> owned, Section section) {
        Set<String> out = new HashSet<>();
        owned.stream().filter(o -> o.section() == section).forEach(o -> out.add(o.key()));
        return out;
    }

    static BrokerConfigNodeStateEntity.Key key(UUID clusterId, UUID nodeId) {
        BrokerConfigNodeStateEntity.Key k = new BrokerConfigNodeStateEntity.Key();
        k.setClusterId(clusterId);
        k.setNodeId(nodeId);
        return k;
    }
}
