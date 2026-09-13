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
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.BrokerConfigService.Source;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
            UUID nodeId,
            String nodeName,
            boolean live,
            State state,
            String detail,
            List<DriftFinding> findings,
            Basis basis,
            Long basisRef) {}

    /**
     * Evaluate now, on an operator's request.
     *
     * <p>Single-flight like the scheduled pass: without the lock two evaluations of
     * the same cluster read the same nodes twice and raced to write the same rows,
     * last one winning.
     */
    @Transactional
    public Report evaluate(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        AtomicReference<Report> report = new AtomicReference<>();
        boolean ran = lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_DRIFT, () -> {
            if (applyInFlight(clusterId)) {
                return;
            }
            report.set(evaluateInternal(clusterId));
        });
        if (!ran) {
            throw new ConflictException(
                    "evaluation-in-progress",
                    "An evaluation of this cluster is already running. Its result will appear when it finishes.");
        }
        if (report.get() == null) {
            throw new ConflictException(
                    "apply-in-progress",
                    "A configuration apply is running on this cluster. A node read while it is half applied would"
                            + " be reported as drifted; evaluate again when the apply has finished.");
        }
        return report.get();
    }

    /** The scheduled pass: every declared cluster, under the cluster lock, never throwing. */
    public void evaluateAll() {
        for (var header : declarations.findAll()) {
            UUID clusterId = header.getClusterId();
            try {
                lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_DRIFT, () -> {
                    if (applyInFlight(clusterId)) {
                        log.debug("Skipping drift evaluation for cluster {}: an apply is running", clusterId);
                        return;
                    }
                    evaluateInternal(clusterId);
                });
            } catch (RuntimeException e) {
                log.warn("Drift evaluation for cluster {} failed: {}", clusterId, e.getMessage());
            }
        }
    }

    /**
     * Whether an apply holds the cluster right now.
     *
     * <p>A node read in the middle of an apply is half written by definition, and
     * recording that as DRIFTED raises an alert about work that is going correctly.
     * Skipping is safe because the apply publishes its own evaluation when it
     * commits.
     *
     * <p>ponytail: probe-then-act, so an apply starting in the microseconds after the
     * probe is still evaluated through. The post-apply evaluation corrects the row,
     * so the window costs at most one transient reading; holding CONFIG_APPLY for the
     * whole pass would make evaluation block applies, which is the worse trade.
     */
    private boolean applyInFlight(UUID clusterId) {
        return lock.isHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY);
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
            BrokerConfigNodeStateEntity row = nodeStates
                    .findById(key(clusterId, node.nodeId()))
                    .orElseGet(() -> new BrokerConfigNodeStateEntity(clusterId, node.nodeId()));
            NodeReport report =
                    report(node, plan, revision.revision(), basis(row, revision.revision(), revision.source()));
            row.record(
                    report.state(),
                    report.detail(),
                    report.state() == State.IN_SYNC || report.state() == State.DRIFTED ? revision.revision() : null,
                    mapper.writeValueAsString(report.findings()),
                    report.basis(),
                    report.basisRef());
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

    /**
     * Why a node that matches the declaration matches it (changeset 025).
     *
     * <p>An apply records {@code VERIFIED_APPLY} against the revision it wrote, and
     * that is the strongest evidence there is, so it survives every later evaluation
     * of the same revision. Failing that, a declaration adopted from the cluster
     * matches because it was copied from it — the broker was never written. Anything
     * else is an evaluation that simply found them equal, which is what a
     * CONFIG_MANAGED cluster's agreement always is.
     */
    private static Basis basis(BrokerConfigNodeStateEntity row, int revision, Source source) {
        if (row.basis() == Basis.VERIFIED_APPLY
                && row.getVerifiedRevision() != null
                && row.getVerifiedRevision() == revision) {
            return Basis.VERIFIED_APPLY;
        }
        return source == Source.ADOPT ? Basis.ADOPTED : Basis.OBSERVED_MATCH;
    }

    /**
     * The evidence, in the words the drift tab shows. Never silent: a node that
     * agrees says why it agrees, so "in sync" after an adoption cannot be mistaken
     * for "in sync" after an apply.
     */
    static String why(Basis basis, int revision) {
        if (basis == null) {
            return "";
        }
        return switch (basis) {
            case VERIFIED_APPLY -> "Studio applied it and read it back.";
            case ADOPTED -> "Revision " + revision + " was adopted from this cluster; no broker was written.";
            case OBSERVED_MATCH -> "This evaluation found them equal; Studio has not written to this node.";
        };
    }

    /** Read the planner's answer for one node as drift. */
    static NodeReport report(ObservedNodeConfig node, Plan plan, int revision, Basis basisIfInSync) {
        if (!plan.valid()) {
            return new NodeReport(
                    node.nodeId(),
                    node.nodeName(),
                    node.live(),
                    State.NOT_EVALUATED,
                    "The declaration cannot be compared: "
                            + plan.violations().getFirst().message(),
                    List.of(),
                    null,
                    null);
        }
        if (!node.live()) {
            return new NodeReport(
                    node.nodeId(),
                    node.nodeName(),
                    false,
                    State.NOT_EVALUATED,
                    "Not live. A backup inherits what its primary holds once it becomes active.",
                    List.of(),
                    null,
                    null);
        }
        if (node.unavailableReason() != null) {
            return new NodeReport(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    State.UNREACHABLE,
                    node.unavailableReason(),
                    List.of(),
                    null,
                    null);
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
        Basis basis = state == State.IN_SYNC ? basisIfInSync : null;
        String detail = state == State.IN_SYNC
                ? "Matches revision " + revision + ". " + why(basis, revision)
                : deduped.size() + " findings.";
        Long basisRef = basis == Basis.ADOPTED ? (long) revision : null;
        return new NodeReport(node.nodeId(), node.nodeName(), true, state, detail, deduped, basis, basisRef);
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
