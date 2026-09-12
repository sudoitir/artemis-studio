package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.ManagementRefusal;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigPlanner;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.HazardClass;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.OwnedItem;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Hazard;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.NodePlan;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Step;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PlanOptions;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigApplyEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigApplyEntity.Outcome;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigApplyRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigNodeStateRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerConfigOwnedItemRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterLock;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.NodeApply;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.StepApply;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.StepStatus;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyOutcome.Verification;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The apply engine (ADR-0067 D3–D7, D12): a plan computed from what every live node
 * is running, hazards named before any write, and — for a real run — the canary
 * first, re-read and verified, then the rest one at a time, halting on the first
 * failure. Nothing is rolled back and nothing beyond the failed node is touched.
 *
 * <p>Deliberately not a {@code LifecycleKind}: the lifecycle fan-out applies to every
 * node regardless of individual failures (ADR-0049), which is the wrong shape for a
 * setting that can stop producers on every node at once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerConfigApplyService {

    static final String AUDIT_APPLY = "APPLY_BROKER_CONFIG";

    private final BrokerConfigService configs;
    private final BrokerConfigReads reads;
    private final BrokerConfigOperations ops;
    private final BrokerConfigApplyRepository applies;
    private final BrokerConfigOwnedItemRepository ownedItems;
    private final BrokerConfigNodeStateRepository nodeStates;
    private final BrokerConfigDriftService drift;
    private final ClusterAccessGuard clusterAccess;
    private final CapabilityLedger capabilities;
    private final SettingsService settings;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ClusterLock lock;
    private final SseHub sseHub;
    private final ObjectMapper mapper;

    // ---- entry points ------------------------------------------------------

    /** A dry run: the plan, the hazards, no write. Audited, like every lifecycle dry run. */
    @Transactional(noRollbackFor = {BulkCapExceededException.class, BrokerConfigInvalidException.class})
    public BrokerConfigApplyOutcome plan(UUID clusterId, BrokerConfigApplyRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_APPLY);
        Prepared p = prepare(clusterId, request);
        BrokerConfigApplyEntity row = applies.save(new BrokerConfigApplyEntity(
                clusterId,
                p.revision.id(),
                mapper.writeValueAsString(p.plan),
                actorResolver.resolve().displayName(),
                p.plan.canaryNodeId(),
                true));
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                AUDIT_APPLY,
                "cluster",
                configs.clusterName(clusterId),
                clusterId,
                null,
                params(p, request),
                true);
        row.attachAudit(event.getId());
        List<NodeApply> nodes = preview(p.plan);
        BrokerConfigApplyOutcome outcome = new BrokerConfigApplyOutcome(
                row.getId(),
                true,
                Outcome.DRY_RUN,
                p.revision.revision(),
                p.plan,
                nodes,
                p.stepCap,
                p.overCap,
                previewSummary(p),
                event.getId());
        row.finish(Outcome.DRY_RUN, outcome.summary(), mapper.writeValueAsString(nodes));
        applies.save(row);
        audit.finish(event, false, 0, null, nodes);
        return outcome;
    }

    /** A real run: refuse anything the preview did not cover, then canary, verify, continue, halt. */
    @Transactional(noRollbackFor = {BulkCapExceededException.class, BrokerConfigInvalidException.class})
    public BrokerConfigApplyOutcome apply(UUID clusterId, BrokerConfigApplyRequest request) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_APPLY);
        AtomicReference<BrokerConfigApplyOutcome> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        boolean ran = lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY, () -> {
            try {
                result.set(run(clusterId, request));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (!ran) {
            throw new ConflictException(
                    "apply-in-progress",
                    "Another configuration apply is running on this cluster. Wait for it to finish.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    @Transactional(readOnly = true)
    public List<BrokerConfigApplyEntity> history(UUID clusterId, int limit) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return applies.findByClusterIdOrderByStartedAtDesc(
                clusterId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    @Transactional(readOnly = true)
    public Optional<BrokerConfigApplyEntity> one(UUID clusterId, long id) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return applies.findById(id).filter(a -> a.getClusterId().equals(clusterId));
    }

    /** One past apply with the plan that was shown and what happened per node. */
    public record Detail(BrokerConfigApplyEntity apply, Plan plan, List<NodeApply> nodes) {}

    @Transactional(readOnly = true)
    public Optional<Detail> detail(UUID clusterId, long id) {
        return one(clusterId, id)
                .map(a -> new Detail(
                        a,
                        mapper.readValue(a.getPlan(), Plan.class),
                        a.getOutcomeDetail() == null
                                ? List.of()
                                : mapper.readValue(
                                        a.getOutcomeDetail(),
                                        mapper.getTypeFactory().constructCollectionType(List.class, NodeApply.class))));
    }

    // ---- the run --------------------------------------------------------------

    private record Prepared(
            BrokerConfigService.CurrentRevision revision,
            Plan plan,
            List<ObservedNodeConfig> observed,
            Map<UUID, BrokerNodeEntity> nodes,
            Set<OwnedItem> owned,
            int stepCap,
            boolean overCap) {}

    private Prepared prepare(UUID clusterId, BrokerConfigApplyRequest request) {
        BrokerConfigService.CurrentRevision revision = configs.current(clusterId)
                .orElseThrow(() -> new ConflictException(
                        "no-declaration", "Declare the cluster's configuration before applying it."));
        if (request.revision() != null && request.revision() != revision.revision()) {
            throw new ConflictException(
                    "stale-revision",
                    "Revision " + request.revision() + " is not current; revision " + revision.revision()
                            + " is. Preview again.");
        }
        Set<OwnedItem> owned = drift.owned(clusterId);
        ReadScope scope = ReadScope.of(
                revision.document(),
                BrokerConfigDriftService.ownedKeys(owned, Section.ADDRESS_SETTING),
                BrokerConfigDriftService.ownedKeys(owned, Section.SECURITY_SETTING));
        Map<UUID, BrokerNodeEntity> nodes = new LinkedHashMap<>();
        reads.targets(clusterId).forEach(n -> nodes.put(n.getId(), n));
        List<ObservedNodeConfig> observed = reads.observe(clusterId, scope);
        PlanOptions options = new PlanOptions(
                request.nodeIds(), request.canaryNodeId(), request.removeUndeclared(), false, List.of());
        Plan plan = BrokerConfigPlanner.plan(revision.document(), observed, owned, options);
        if (!plan.valid()) {
            throw new BrokerConfigInvalidException(plan.violations());
        }
        int cap = settings.configApplyStepCap();
        return new Prepared(revision, plan, observed, nodes, owned, cap, plan.stepCount() > cap);
    }

    private BrokerConfigApplyOutcome run(UUID clusterId, BrokerConfigApplyRequest request) {
        Prepared p = prepare(clusterId, request);
        if (request.expectedPlanHash() != null && !request.expectedPlanHash().equals(p.plan.planHash())) {
            throw new ConflictException(
                    "plan-changed",
                    "The cluster changed since the preview: the plan is no longer the one that was confirmed."
                            + " Preview again.");
        }
        List<String> missing = new ArrayList<>(p.plan.highHazardIds());
        missing.removeAll(request.acknowledgedHazards());
        if (!missing.isEmpty()) {
            throw new HazardNotAcknowledgedException(missing);
        }
        if (p.overCap && !request.override()) {
            throw new BulkCapExceededException(p.plan.stepCount(), p.stepCap);
        }
        if (p.plan.stepCount() == 0) {
            return nothingToDo(clusterId, p, request);
        }

        BrokerConfigApplyEntity row = applies.save(new BrokerConfigApplyEntity(
                clusterId,
                p.revision.id(),
                mapper.writeValueAsString(p.plan),
                actorResolver.resolve().displayName(),
                p.plan.canaryNodeId(),
                false));
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                AUDIT_APPLY,
                "cluster",
                configs.clusterName(clusterId),
                clusterId,
                null,
                params(p, request),
                false);
        row.attachAudit(event.getId());

        boolean lockoutGuard = p.plan.hazards().stream().anyMatch(h -> h.kind() == HazardKind.MANAGEMENT_ACCESS);
        List<NodePlan> order = new ArrayList<>();
        p.plan.nodes().stream()
                .filter(n -> n.readable() && n.nodeId().equals(p.plan.canaryNodeId()))
                .forEach(order::add);
        p.plan.nodes().stream()
                .filter(n -> n.readable() && !n.nodeId().equals(p.plan.canaryNodeId()))
                .forEach(order::add);

        List<NodeApply> results = new ArrayList<>();
        boolean halted = false;
        String haltReason = null;
        long applied = 0;
        for (NodePlan node : order) {
            if (halted) {
                results.add(
                        notAttempted(node, p.plan.canaryNodeId(), "Not attempted: the run halted on an earlier node."));
                continue;
            }
            NodeApply result = applyTo(clusterId, p, node, lockoutGuard, event.getId());
            results.add(result);
            recordNodeState(clusterId, p.revision.revision(), row.getId(), result);
            applied += result.steps().stream()
                    .filter(s -> s.status() == StepStatus.APPLIED)
                    .count();
            if (result.anyFailed()) {
                halted = true;
                haltReason = result.steps().stream()
                        .filter(s -> s.status() == StepStatus.FAILED || s.verified() == Verification.MISMATCH)
                        .findFirst()
                        .map(s -> node.nodeName() + ", " + s.description() + ": "
                                + (s.error() != null ? s.error() : "read-back did not match"))
                        .orElse(node.nodeName());
            }
        }
        for (NodePlan node : p.plan.nodes()) {
            if (!node.readable()) {
                results.add(skipped(node));
            }
        }

        Outcome outcome =
                halted ? (applied == 0 && results.size() == 1 ? Outcome.FAILED : Outcome.HALTED) : Outcome.APPLIED;
        String summary = summarise(outcome, results, haltReason, order.size());
        row.finish(outcome, summary, mapper.writeValueAsString(results));
        applies.save(row);
        audit.finish(event, halted, applied, halted ? summary : null, results);
        publishAfterCommit(clusterId);
        return new BrokerConfigApplyOutcome(
                row.getId(),
                false,
                outcome,
                p.revision.revision(),
                p.plan,
                results,
                p.stepCap,
                p.overCap,
                summary,
                event.getId());
    }

    private BrokerConfigApplyOutcome nothingToDo(UUID clusterId, Prepared p, BrokerConfigApplyRequest request) {
        List<NodeApply> nodes = preview(p.plan);
        BrokerConfigApplyEntity row = applies.save(new BrokerConfigApplyEntity(
                clusterId,
                p.revision.id(),
                mapper.writeValueAsString(p.plan),
                actorResolver.resolve().displayName(),
                p.plan.canaryNodeId(),
                false));
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                AUDIT_APPLY,
                "cluster",
                configs.clusterName(clusterId),
                clusterId,
                null,
                params(p, request),
                false);
        row.attachAudit(event.getId());
        String summary = "Every live node already matches revision " + p.revision.revision() + "; nothing was written.";
        row.finish(Outcome.APPLIED, summary, mapper.writeValueAsString(nodes));
        applies.save(row);
        audit.finish(event, false, 0, null, nodes);
        return new BrokerConfigApplyOutcome(
                row.getId(),
                false,
                Outcome.APPLIED,
                p.revision.revision(),
                p.plan,
                nodes,
                p.stepCap,
                p.overCap,
                summary,
                event.getId());
    }

    /**
     * One node: every pending step in order, stopping at the first failure; then one
     * read-back to verify what was applied. A {@code MISMATCH} is a failure. When a
     * security change could have locked Studio out, the read-back itself must
     * succeed.
     */
    private NodeApply applyTo(UUID clusterId, Prepared p, NodePlan node, boolean lockoutGuard, Long auditEventId) {
        BrokerNodeEntity entity = p.nodes.get(node.nodeId());
        boolean canary = node.nodeId().equals(p.plan.canaryNodeId());
        List<StepApply> steps = new ArrayList<>();
        JolokiaBrokerClient client;
        String broker;
        try {
            client = reads.client(clusterId, entity);
            broker = client.resolveBrokerObjectName();
        } catch (BrokerConnectionException e) {
            for (Step s : node.steps()) {
                steps.add(stepApply(
                        s,
                        s.already() ? StepStatus.ALREADY : StepStatus.NOT_ATTEMPTED,
                        Verification.NOT_VERIFIED,
                        null));
            }
            return new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    e.getMessage(),
                    steps,
                    "Could not connect: " + e.getMessage());
        }
        boolean failed = false;
        for (Step s : node.steps()) {
            if (s.already()) {
                steps.add(stepApply(s, StepStatus.ALREADY, Verification.VERIFIED, null));
                continue;
            }
            if (failed) {
                steps.add(stepApply(s, StepStatus.NOT_ATTEMPTED, Verification.NOT_VERIFIED, null));
                continue;
            }
            try {
                StepStatus status = execute(client, broker, p.revision.document(), s);
                capabilities.recordWriteSucceeded(clusterId);
                steps.add(stepApply(s, status, Verification.NOT_VERIFIED, null));
                recordOwnership(clusterId, p.revision.id(), s, status);
            } catch (ManagementRefusal e) {
                if (e.kind() == ManagementRefusal.Kind.ALREADY) {
                    capabilities.recordWriteSucceeded(clusterId);
                    steps.add(stepApply(s, StepStatus.ALREADY, Verification.NOT_VERIFIED, null));
                    recordOwnership(clusterId, p.revision.id(), s, StepStatus.ALREADY);
                    continue;
                }
                failed = true;
                steps.add(stepApply(s, StepStatus.FAILED, Verification.NOT_VERIFIED, e.getMessage()));
            } catch (BrokerConnectionException e) {
                if (e.kind() == BrokerConnectionException.Kind.UNAUTHORIZED) {
                    capabilities.recordWriteRefused(clusterId, e.getMessage());
                }
                failed = true;
                steps.add(stepApply(s, StepStatus.FAILED, Verification.NOT_VERIFIED, e.getMessage()));
            }
        }
        if (failed) {
            return new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    null,
                    steps,
                    "Halted on this node; later steps were not attempted.");
        }
        return verify(clusterId, p, node, entity, canary, steps, lockoutGuard);
    }

    /** Re-read the node and re-plan it: anything still pending that was applied is a mismatch. */
    private NodeApply verify(
            UUID clusterId,
            Prepared p,
            NodePlan node,
            BrokerNodeEntity entity,
            boolean canary,
            List<StepApply> steps,
            boolean lockoutGuard) {
        ReadScope scope = ReadScope.of(
                p.revision.document(),
                BrokerConfigDriftService.ownedKeys(p.owned, Section.ADDRESS_SETTING),
                BrokerConfigDriftService.ownedKeys(p.owned, Section.SECURITY_SETTING));
        ObservedNodeConfig after = reads.observe(clusterId, entity, scope);
        if (after.unavailableReason() != null) {
            List<StepApply> out = new ArrayList<>();
            for (StepApply s : steps) {
                out.add(new StepApply(
                        s.stepId(),
                        s.section(),
                        s.key(),
                        s.op(),
                        s.description(),
                        s.status(),
                        s.status() == StepStatus.APPLIED
                                ? (lockoutGuard ? Verification.MISMATCH : Verification.UNVERIFIABLE)
                                : s.verified(),
                        s.error()));
            }
            String note = lockoutGuard
                    ? "The verification read was refused after a security change covering the management address."
                            + " Studio may have lost its own access and cannot revert it: " + after.unavailableReason()
                    : "Applied, but the verification read failed: " + after.unavailableReason();
            return new NodeApply(node.nodeId(), node.nodeName(), true, canary, null, out, note);
        }
        // Every applied item is now Studio-owned for the re-plan, so a removal is judged the same way.
        Set<OwnedItem> owned = new HashSet<>(p.owned);
        Plan again = BrokerConfigPlanner.plan(
                p.revision.document(),
                List.of(after),
                owned,
                new PlanOptions(Set.of(node.nodeId()), node.nodeId(), false, false, List.of()));
        // Keyed by section and key, not by step id: the id carries the op, and a setting
        // that was ADDed is REPLACEd in the re-plan, so an id comparison would call every
        // mismatched setting verified.
        Set<String> stillPending = new HashSet<>();
        again.nodes().stream()
                .filter(n -> n.nodeId().equals(node.nodeId()))
                .flatMap(n -> n.steps().stream())
                .filter(s -> !s.already())
                .forEach(s -> stillPending.add(s.section() + ":" + s.key()));
        List<StepApply> out = new ArrayList<>();
        boolean mismatch = false;
        for (StepApply s : steps) {
            Verification v = s.verified();
            if (s.status() == StepStatus.APPLIED || s.status() == StepStatus.ALREADY) {
                String item = s.section() + ":" + s.key();
                if (s.op() == Op.REMOVE) {
                    v = stillPending.contains(item) ? Verification.MISMATCH : Verification.VERIFIED;
                } else if (stillPending.contains(item)) {
                    v = Verification.MISMATCH;
                } else if (s.section() == Section.ADDRESS_SETTING
                        && !echoesEveryDeclaredKey(p.revision.document(), s.key(), after)) {
                    v = Verification.UNVERIFIABLE;
                } else {
                    v = Verification.VERIFIED;
                }
            }
            mismatch |= v == Verification.MISMATCH;
            out.add(new StepApply(s.stepId(), s.section(), s.key(), s.op(), s.description(), s.status(), v, s.error()));
        }
        String note = mismatch
                ? "Read-back after the apply does not match the declaration; the run halted here."
                : "Applied and verified by reading the node back.";
        return new NodeApply(node.nodeId(), node.nodeName(), true, canary, null, out, note);
    }

    /**
     * Write what the apply itself learned about this node into the drift state.
     *
     * <p>A read-back that did not match is drift, and it is drift the moment it is
     * seen: leaving it to the post-apply evaluation meant a mismatch that halted a
     * run could still leave the node reading IN_SYNC if that evaluation was skipped,
     * raced or threw — and the CONFIG_DRIFT condition reads this table, so the alert
     * went unraised too. A clean verify records the strongest basis there is; the
     * evaluation that follows preserves it for as long as the revision stands.
     */
    private void recordNodeState(UUID clusterId, int revision, long applyId, NodeApply result) {
        if (result.unavailableReason() != null) {
            return;
        }
        boolean mismatch = result.steps().stream().anyMatch(s -> s.verified() == Verification.MISMATCH);
        boolean failed = result.steps().stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        if (!mismatch && !failed) {
            writeNodeState(
                    clusterId,
                    result.nodeId(),
                    State.IN_SYNC,
                    "Matches revision " + revision + ". "
                            + BrokerConfigDriftService.why(Basis.VERIFIED_APPLY, revision),
                    revision,
                    Basis.VERIFIED_APPLY,
                    applyId);
            return;
        }
        String detail = result.steps().stream()
                .filter(s -> s.verified() == Verification.MISMATCH)
                .findFirst()
                .map(s -> "Read-back after apply #" + applyId + " did not match: " + s.description())
                .orElseGet(() -> "Apply #" + applyId + " failed on this node; what it wrote is not the declaration.");
        writeNodeState(clusterId, result.nodeId(), State.DRIFTED, detail, revision, null, applyId);
    }

    private void writeNodeState(
            UUID clusterId, UUID nodeId, State state, String detail, int revision, Basis basis, Long basisRef) {
        BrokerConfigNodeStateEntity.Key key = new BrokerConfigNodeStateEntity.Key();
        key.setClusterId(clusterId);
        key.setNodeId(nodeId);
        BrokerConfigNodeStateEntity row =
                nodeStates.findById(key).orElseGet(() -> new BrokerConfigNodeStateEntity(clusterId, nodeId));
        row.record(state, detail, revision, "[]", basis, basisRef);
        nodeStates.save(row);
    }

    private static boolean echoesEveryDeclaredKey(BrokerConfigDocument doc, String match, ObservedNodeConfig after) {
        Map<String, Object> observed = after.addressSettings().getOrDefault(match, Map.of());
        return doc.addressSettings().stream()
                .filter(s -> s.match().equals(match))
                .findFirst()
                .map(s -> observed.keySet().containsAll(s.values().keySet()))
                .orElse(true);
    }

    /** The one management write a step stands for. */
    private StepStatus execute(JolokiaBrokerClient client, String broker, BrokerConfigDocument doc, Step s) {
        switch (s.section()) {
            case ADDRESS -> {
                @SuppressWarnings("unchecked")
                List<String> types = (List<String>) s.after().get("routingTypes");
                ops.createAddress(client, broker, s.key(), new HashSet<>(types));
            }
            case QUEUE -> ops.createQueue(client, broker, s.after());
            case ADDRESS_SETTING -> {
                if (s.op() == Op.REMOVE) {
                    ops.removeAddressSettings(client, broker, s.key());
                } else {
                    AddressSettingDecl decl = doc.addressSettings().stream()
                            .filter(d -> d.match().equals(s.key()))
                            .findFirst()
                            .orElseThrow();
                    ops.addAddressSettings(client, broker, s.key(), decl.values());
                }
            }
            case SECURITY_SETTING -> {
                if (s.op() == Op.REMOVE) {
                    ops.removeSecuritySettings(client, broker, s.key());
                } else {
                    SecuritySettingDecl decl = doc.securitySettings().stream()
                            .filter(d -> d.match().equals(s.key()))
                            .findFirst()
                            .orElseThrow();
                    Map<PermissionType, Set<String>> roles = new java.util.EnumMap<>(PermissionType.class);
                    roles.putAll(decl.permissions());
                    ops.addSecuritySettings(client, broker, s.key(), roles);
                }
            }
            case DIVERT -> {
                if (s.op() == Op.REMOVE) {
                    ops.destroyDivert(client, broker, s.key());
                } else {
                    ops.createDivert(client, broker, s.after());
                }
            }
        }
        return StepStatus.APPLIED;
    }

    private void recordOwnership(UUID clusterId, long revisionId, Step s, StepStatus status) {
        if (s.section() == Section.ADDRESS || s.section() == Section.QUEUE) {
            return;
        }
        BrokerConfigOwnedItemEntity.Key key = new BrokerConfigOwnedItemEntity.Key();
        key.setClusterId(clusterId);
        key.setKind(s.section().name());
        key.setItemKey(s.key());
        if (s.op() == Op.REMOVE) {
            ownedItems.findById(key).ifPresent(ownedItems::delete);
            return;
        }
        if (status != StepStatus.APPLIED) {
            return;
        }
        ownedItems
                .findById(key)
                .ifPresentOrElse(
                        e -> {
                            e.touch(revisionId);
                            ownedItems.save(e);
                        },
                        () -> ownedItems.save(new BrokerConfigOwnedItemEntity(
                                clusterId, s.section().name(), s.key(), revisionId)));
    }

    // ---- shapes ----------------------------------------------------------------

    private static List<NodeApply> preview(Plan plan) {
        List<NodeApply> out = new ArrayList<>();
        for (NodePlan node : plan.nodes()) {
            if (!node.readable()) {
                out.add(skipped(node));
                continue;
            }
            List<StepApply> steps = new ArrayList<>();
            for (Step s : node.steps()) {
                steps.add(stepApply(
                        s, s.already() ? StepStatus.ALREADY : StepStatus.WOULD_APPLY, Verification.NOT_VERIFIED, null));
            }
            boolean canary = node.nodeId().equals(plan.canaryNodeId());
            out.add(new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    null,
                    steps,
                    canary ? "Canary: applied first and read back before any other node." : null));
        }
        return out;
    }

    private static NodeApply skipped(NodePlan node) {
        String note = node.live()
                ? "Unreachable: " + node.unavailableReason()
                : "Not live. A backup inherits what its primary holds once it becomes active.";
        return new NodeApply(
                node.nodeId(), node.nodeName(), node.live(), false, node.unavailableReason(), List.of(), note);
    }

    private static NodeApply notAttempted(NodePlan node, UUID canary, String note) {
        List<StepApply> steps = new ArrayList<>();
        for (Step s : node.steps()) {
            steps.add(stepApply(
                    s, s.already() ? StepStatus.ALREADY : StepStatus.NOT_ATTEMPTED, Verification.NOT_VERIFIED, null));
        }
        return new NodeApply(node.nodeId(), node.nodeName(), true, node.nodeId().equals(canary), null, steps, note);
    }

    private static StepApply stepApply(Step s, StepStatus status, Verification verified, String error) {
        return new StepApply(s.id(), s.section(), s.key(), s.op(), s.description(), status, verified, error);
    }

    private static String previewSummary(Prepared p) {
        long readable = p.plan.nodes().stream().filter(NodePlan::readable).count();
        StringBuilder sb = new StringBuilder();
        sb.append(p.plan.stepCount())
                .append(p.plan.stepCount() == 1 ? " step" : " steps")
                .append(" on ")
                .append(readable)
                .append(readable == 1 ? " live node" : " live nodes")
                .append('.');
        if (p.plan.canaryNodeId() != null) {
            p.plan.nodes().stream()
                    .filter(n -> n.nodeId().equals(p.plan.canaryNodeId()))
                    .findFirst()
                    .ifPresent(n -> sb.append(" Canary: ").append(n.nodeName()).append('.'));
        }
        long high = p.plan.hazards().stream()
                .filter(h -> h.hazardClass() == HazardClass.HIGH)
                .count();
        if (high > 0) {
            sb.append(' ')
                    .append(high)
                    .append(high == 1 ? " high hazard" : " high hazards")
                    .append(" to acknowledge.");
        }
        if (p.overCap) {
            sb.append(" Over the step cap of ").append(p.stepCap).append("; an override is needed.");
        }
        return sb.toString();
    }

    private static String summarise(Outcome outcome, List<NodeApply> nodes, String haltReason, int targeted) {
        long applied = nodes.stream()
                .flatMap(n -> n.steps().stream())
                .filter(s -> s.status() == StepStatus.APPLIED)
                .count();
        if (outcome == Outcome.APPLIED) {
            return "Applied " + applied + (applied == 1 ? " step" : " steps") + " on " + targeted
                    + (targeted == 1 ? " node" : " nodes") + ", each verified by reading it back.";
        }
        long notAttempted = nodes.stream()
                .filter(n -> n.steps().stream().anyMatch(s -> s.status() == StepStatus.NOT_ATTEMPTED))
                .count();
        return "Halted at " + haltReason + ". " + applied + (applied == 1 ? " step was" : " steps were")
                + " applied before that; " + notAttempted + (notAttempted == 1 ? " node was" : " nodes were")
                + " not attempted. Nothing was rolled back. Re-running converges: what already matches is skipped.";
    }

    private static Map<String, Object> params(Prepared p, BrokerConfigApplyRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("revision", p.revision.revision());
        m.put("planHash", p.plan.planHash());
        m.put("stepCount", p.plan.stepCount());
        m.put("canaryNodeId", p.plan.canaryNodeId());
        m.put("nodeIds", request.nodeIds());
        m.put("removeUndeclared", request.removeUndeclared());
        m.put("acknowledgedHazards", request.acknowledgedHazards());
        m.put("override", request.override());
        m.put("hazards", p.plan.hazards().stream().map(Hazard::id).toList());
        return m;
    }

    private void publishAfterCommit(UUID clusterId) {
        Runnable after = () -> {
            sseHub.publish(clusterId, BrokerConfigDriftService.SSE_TOPIC);
            drift.evaluateAfterApply(clusterId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    after.run();
                }
            });
        } else {
            after.run();
        }
    }
}
