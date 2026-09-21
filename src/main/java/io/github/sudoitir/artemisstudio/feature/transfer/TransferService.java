package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.feature.messages.MessagePermissions;
import io.github.sudoitir.artemisstudio.feature.transfer.TransferRunMapper.Derived;
import io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.Finding;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.FindingKind;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanReturnRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanReturnView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferEnd;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditScope;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.broker.AcceptanceProbe;
import io.github.sudoitir.artemisstudio.platform.broker.AcceptanceProbe.Facts;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.artemisstudio.platform.broker.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.platform.broker.FrozenFilter;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.MessageOperations;
import io.github.sudoitir.artemisstudio.platform.broker.StagingQueues;
import io.github.sudoitir.artemisstudio.platform.clusters.CapabilityLedger;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.clusters.ServingNodes;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainRegistry;
import io.github.sudoitir.artemisstudio.platform.clusters.SplitBrainStatus;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator.QueueLocation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Transfer runs (ADR-0097): preview one with no broker side effect, execute it in the background,
 * stop, resume or return it, and read it back. Every action is authorised on both clusters; a target
 * cluster the operator cannot send to answers as one that does not exist.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);
    static final String AUDIT_ACTION = "message.transfer";
    private static final int HISTORY = 100;

    private final TransferRunRepository runs;
    private final TransferRunMapper views;
    private final TransferRunner runner;
    private final TransferNodes nodes;
    private final ClusterDirectory directory;
    private final SplitBrainRegistry splitBrain;
    private final CapabilityLedger capabilities;
    private final QueueLocator locator;
    private final MessageOperations messages;
    private final AcceptanceProbe probe;
    private final StagingQueues staging;
    private final ClusterAccessGuard access;
    private final PermissionResolver permissions;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SettingsService settings;
    private final ObjectMapper json;

    // ---- preview -----------------------------------------------------------

    /**
     * Resolve both ends, freeze the selection at now, estimate it, and check the target can accept it.
     * Reads only: no staging queue is created and no message is touched. Writes no audit event.
     */
    public TransferRunView preview(UUID clusterId, TransferPreviewRequest request) {
        TransferMode mode = request.mode();
        access.requireCluster(clusterId, mode.sourcePermission());
        access.requireCluster(request.targetClusterId(), MessagePermissions.MESSAGE_SEND);
        TransferSelection selection = normalise(request.selection());

        ClusterNode source = nodes.node(clusterId, request.sourceNodeId());
        ClusterNode target = nodes.node(request.targetClusterId(), request.targetNodeId());
        for (ClusterNode node : List.of(source, target)) {
            if (node.getArtemisNodeId() == null || node.getJolokiaUrl() == null) {
                throw new TransferRefusedException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "transfer-node-unmanaged",
                        "Node %s has not been read yet or has no management URL, so it cannot take part in a transfer."
                                .formatted(node.getName()));
            }
        }
        QueueLocation from = locate(clusterId, request.sourceQueue(), source)
                .orElseThrow(() -> new NotFoundException("queue", request.sourceQueue()));
        Optional<QueueLocation> to = locate(request.targetClusterId(), request.targetQueue(), target);
        String targetAddress =
                request.targetAddress() != null && !request.targetAddress().isBlank()
                        ? request.targetAddress().strip()
                        : to.map(QueueLocation::address).orElse(request.targetQueue());
        String targetRoutingType = to.map(QueueLocation::routingType).orElse("ANYCAST");
        boolean sameCluster = clusterId.equals(request.targetClusterId());
        boolean sameNode = sameCluster && source.getArtemisNodeId().equals(target.getArtemisNodeId());
        Instant t0 = Instant.now();

        JolokiaBrokerClient sourceClient = nodes.client(source);
        Long count = estimate(sourceClient, from, selection, t0);
        Facts sourceFacts = probe.read(sourceClient, from.address(), from.queueName(), from.routingType());
        Long bytes = projectedBytes(count, sourceFacts);

        boolean targetLive = TransferNodes.live(target);
        Facts targetFacts = targetLive
                ? probe.read(nodes.client(target), targetAddress, request.targetQueue(), targetRoutingType)
                : null;
        int threshold = settings.intValue(TransferSettings.CAPACITY_THRESHOLD_PERCENT);
        List<Finding> findings = new ArrayList<>(TargetAcceptance.evaluate(
                new TargetAcceptance.Input(
                        targetFacts,
                        count,
                        bytes,
                        sameNode && from.queueName().equals(request.targetQueue()),
                        sameCluster,
                        targetLive,
                        TransferNodes.backup(target),
                        splitBrain.statusFor(request.targetClusterId(), target.getArtemisNodeId())
                                == SplitBrainStatus.CRITICAL,
                        threshold),
                targetAddress,
                request.targetQueue()));
        findings.addAll(capabilityFindings(mode, clusterId, source, target, sameNode));

        Instant now = Instant.now();
        TransferRunEntity run = runs.save(TransferRunEntity.builder()
                .mode(mode)
                .sourceClusterId(clusterId)
                .sourceNodeId(source.getId())
                .sourceNodeName(source.getName())
                .sourceArtemisNodeId(source.getArtemisNodeId())
                .sourceQueue(from.queueName())
                .sourceAddress(from.address())
                .sourceRoutingType(from.routingType())
                .targetClusterId(request.targetClusterId())
                .targetNodeId(target.getId())
                .targetNodeName(target.getName())
                .targetArtemisNodeId(target.getArtemisNodeId())
                .targetQueue(request.targetQueue())
                .targetAddress(targetAddress)
                .targetRoutingType(targetRoutingType)
                .sameNode(sameNode)
                .selection(json.writeValueAsString(selection))
                .findings(json.writeValueAsString(findings))
                .t0(t0)
                .planHash(planHash(
                        mode, source, target, from.queueName(), request.targetQueue(), targetAddress, selection, t0))
                .estimate(count)
                .estimateBytes(bytes)
                .username(handoff.capture().actor().displayName())
                .createdAt(now)
                .expiresAt(now.plus(PREVIEW_LIFETIME))
                .build());
        return view(run);
    }

    private static TransferSelection normalise(TransferSelection selection) {
        return switch (selection.kind()) {
            case IDS -> {
                if (selection.ids() == null || selection.ids().isEmpty()) {
                    throw new TransferRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "transfer-nothing-selected", "No message id was given.");
                }
                yield new TransferSelection(SelectionKind.IDS, List.copyOf(new LinkedHashSet<>(selection.ids())), null);
            }
            case FILTER -> {
                if (selection.filter() == null || selection.filter().isBlank()) {
                    throw new TransferRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "transfer-nothing-selected",
                            "A filter selection needs a filter. Choose the whole queue instead to take everything.");
                }
                yield new TransferSelection(
                        SelectionKind.FILTER, null, selection.filter().strip());
            }
            case ALL -> new TransferSelection(SelectionKind.ALL, null, null);
        };
    }

    /** The queue's copy on this node, or on another endpoint of the same broker node. */
    private Optional<QueueLocation> locate(UUID clusterId, String queue, ClusterNode node) {
        List<UUID> sameBroker = directory.nodes(clusterId).stream()
                .filter(n -> node.getArtemisNodeId().equals(n.getArtemisNodeId()))
                .map(ClusterNode::getId)
                .toList();
        List<QueueLocation> found = locator.locate(clusterId, queue);
        return found.stream()
                .filter(l -> l.nodeId().equals(node.getId()))
                .findFirst()
                .or(() -> found.stream()
                        .filter(l -> sameBroker.contains(l.nodeId()))
                        .findFirst());
    }

    /** The selection's size: exact for ids, the broker's count of the frozen filter otherwise; null when the source did not say. */
    private Long estimate(JolokiaBrokerClient client, QueueLocation from, TransferSelection selection, Instant t0) {
        if (selection.kind() == SelectionKind.IDS) {
            return (long) selection.ids().size();
        }
        try {
            String mbean = BrokerMBeans.queue(
                    client.resolveBrokerObjectName(), from.address(), from.queueName(), from.routingType());
            return messages.countMessages(client, mbean, FrozenFilter.compose(selection.filter(), t0));
        } catch (BrokerConnectionException e) {
            return null;
        }
    }

    /** The count times the source queue's mean persistent message size; null when either is unknown. */
    static Long projectedBytes(Long count, Facts source) {
        if (count == null
                || source.persistentSize() == null
                || source.messageCount() == null
                || source.messageCount() <= 0) {
            return null;
        }
        return count * (source.persistentSize() / source.messageCount());
    }

    /** What the nodes' connectivity and Studio's rights on them say, beside the target's own verdict. */
    private List<Finding> capabilityFindings(
            TransferMode mode, UUID clusterId, ClusterNode source, ClusterNode target, boolean sameNode) {
        List<Finding> out = new ArrayList<>();
        if (!TransferNodes.live(source) || TransferNodes.backup(source)) {
            out.add(new Finding(
                    FindingKind.REFUSE,
                    "source-not-live",
                    "The source node %s is not live now, so its messages cannot be taken.".formatted(source.getName()),
                    null));
        }
        boolean relay = mode == TransferMode.COPY || !sameNode;
        if (relay) {
            for (ClusterNode node : sameNode ? List.of(source) : List.of(source, target)) {
                if (node.getCoreUrl() == null) {
                    out.add(new Finding(
                            FindingKind.REFUSE,
                            node == source ? "source-no-core" : "target-no-core",
                            "Node %s has no Core connection, which a transfer relays messages over."
                                    .formatted(node.getName()),
                            BrokerXmlSnippets.CORE_ACCEPTOR));
                }
            }
        }
        if (mode == TransferMode.MOVE) {
            capabilities
                    .managementWrite(clusterId)
                    .ifPresentOrElse(
                            assessment -> {
                                if (assessment.status()
                                        != io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities
                                                .CapabilityStatus.AVAILABLE) {
                                    out.add(new Finding(
                                            FindingKind.REFUSE,
                                            "management-write",
                                            "A move takes messages off the source through management operations, and "
                                                    + assessment.reason(),
                                            assessment.brokerXmlSnippet()));
                                }
                            },
                            () -> out.add(new Finding(
                                    FindingKind.UNKNOWN,
                                    "management-write",
                                    "Whether the source broker allows Studio's management writes has not been"
                                            + " established yet; a move needs them.",
                                    BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING)));
            if (!sameNode) {
                out.add(new Finding(
                        FindingKind.UNKNOWN,
                        "staging-rights",
                        "A move parks its messages in a queue named %s<run> on the source broker. Whether Studio's"
                                        .formatted(StagingQueues.PREFIX)
                                + " broker user may create and consume it is known only when the run starts; it"
                                + " fails then, before moving anything, if it may not.",
                        BrokerXmlSnippets.STAGING_SECURITY_SETTING));
            }
        }
        return out;
    }

    /** SHA-256 over what the run will do: both ends, the mode, the selection, and its frozen moment. */
    static String planHash(
            TransferMode mode,
            ClusterNode source,
            ClusterNode target,
            String sourceQueue,
            String targetQueue,
            String targetAddress,
            TransferSelection selection,
            Instant t0) {
        String plan = String.join(
                "\n",
                mode.name(),
                source.getClusterId().toString(),
                source.getArtemisNodeId(),
                sourceQueue,
                target.getClusterId().toString(),
                target.getArtemisNodeId(),
                targetQueue,
                targetAddress,
                selection.kind().name(),
                String.valueOf(selection.ids()),
                String.valueOf(selection.filter()),
                Long.toString(t0.toEpochMilli()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(plan.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- execute, stop, resume, return ---------------------------------------

    /**
     * Start the previewed run in the background and return at once. Refused when the plan is not the one
     * previewed, the preview expired or already ran, the preview refused the transfer, a warning was not
     * acknowledged, a move was not confirmed by its source queue's name, the selection is over the cap
     * without the override, another run is active on the source queue, or too many runs are active.
     */
    public TransferRunView execute(UUID clusterId, UUID runId, TransferExecuteRequest request) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        if (run.getState() != TransferState.PREVIEWED) {
            throw new ConflictException(
                    "transfer-run-started", "This transfer has already been started. Preview again to run it again.");
        }
        if (!run.getPlanHash().equals(request.planHash())) {
            throw new ConflictException(
                    "transfer-plan-mismatch",
                    "This is not the plan that was previewed. Preview again and confirm what it shows.");
        }
        if (Instant.now().isAfter(run.getExpiresAt())) {
            throw new TransferRefusedException(
                    HttpStatus.GONE,
                    "transfer-preview-expired",
                    "This preview expired at %s; the queues may have changed since. Preview again."
                            .formatted(run.getExpiresAt()));
        }
        List<Finding> findings = findings(run);
        List<String> refusals = findings.stream()
                .filter(f -> f.kind() == FindingKind.REFUSE)
                .map(Finding::words)
                .toList();
        if (!refusals.isEmpty()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "transfer-refused", String.join(" ", refusals));
        }
        Set<String> acknowledged = request.acknowledged() == null ? Set.of() : Set.copyOf(request.acknowledged());
        List<String> missing = findings.stream()
                .filter(f -> f.kind() == FindingKind.WARN && !acknowledged.contains(f.code()))
                .map(Finding::code)
                .toList();
        if (!missing.isEmpty()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-unacknowledged",
                    "Acknowledge every warning the preview raised before running it: " + String.join(", ", missing)
                            + ".");
        }
        if (run.getMode() == TransferMode.MOVE && !run.getSourceQueue().equals(request.confirmQueue())) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-confirmation-mismatch",
                    "Type the source queue's name, %s, to confirm the move.".formatted(run.getSourceQueue()));
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        if (run.getEstimate() != null && run.getEstimate() > cap && !request.override()) {
            throw new BulkCapExceededException(run.getEstimate(), cap);
        }

        Operator operator = handoff.capture();
        claim(run, Set.of(TransferState.PREVIEWED), TransferState.RUNNING);
        run = reload(runId);
        run.begin(
                TransferState.RUNNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                Instant.now());
        run.overrideCap(request.override());
        beginAudit(run, operator, AUDIT_ACTION, auditParams(run));
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    /** Ask a running run to stop: the batch in flight finishes, then the run stops, resumable. */
    public TransferRunView stop(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        if (!runner.requestStop(runId)) {
            throw new ConflictException(
                    "transfer-run-not-running", "This transfer is not running, so there is nothing to stop.");
        }
        Operator operator = handoff.capture();
        AuditEvent event = childOf(
                run.getAuditEventId(),
                () -> audit.begin(
                        operator.actor(),
                        AUDIT_ACTION + ".stop",
                        "QUEUE",
                        run.getSourceQueue(),
                        run.getSourceClusterId(),
                        run.getSourceNodeId(),
                        Map.of("runId", runId.toString()),
                        false));
        audit.succeed(event, 0);
        return view(run);
    }

    /** Continue a stopped, interrupted or failed run where it left off. */
    public TransferRunView resume(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        requireResumable(run);
        Operator operator = handoff.capture();
        claim(run, TransferState.RESUMABLE, TransferState.RUNNING);
        run = reload(runId);
        Long previous = run.getAuditEventId();
        run.begin(
                TransferState.RUNNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                Instant.now());
        TransferRunEntity resumed = run;
        childOf(previous, () -> {
            beginAudit(resumed, operator, AUDIT_ACTION + ".resume", auditParams(resumed));
            return null;
        });
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    /** Put every message a stopped, interrupted or failed move holds in staging back on its source queue. */
    public TransferRunView returnToSource(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        access.requireCluster(run.getSourceClusterId(), MessagePermissions.MESSAGE_MOVE);
        requireReadable(run);
        requireResumable(run);
        if (run.getMode() != TransferMode.MOVE || run.isSameNode()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-not-returnable",
                    "Only a move between two nodes holds messages in staging; this run holds none to return.");
        }
        Operator operator = handoff.capture();
        claim(run, TransferState.RESUMABLE, TransferState.RETURNING);
        run = reload(runId);
        Long previous = run.getAuditEventId();
        run.begin(
                TransferState.RETURNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                Instant.now());
        TransferRunEntity returning = run;
        AuditEvent event = childOf(
                previous,
                () -> audit.begin(
                        operator.actor(),
                        AUDIT_ACTION + ".return",
                        "QUEUE",
                        returning.getSourceQueue(),
                        returning.getSourceClusterId(),
                        returning.getSourceNodeId(),
                        Map.of("runId", runId.toString(), "stagingQueue", StagingQueues.queueName(runId)),
                        false));
        run.attachAudit(event.getId(), null);
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    private void requireResumable(TransferRunEntity run) {
        if (!run.getState().resumable()) {
            throw new ConflictException(
                    "transfer-run-not-resumable",
                    "This transfer is %s; only a stopped, interrupted or failed run can be resumed or returned."
                            .formatted(run.getState()));
        }
    }

    /**
     * Move the run from {@code from} to {@code to}, once. Refused when too many runs are active, when
     * another run is active on the same source queue, or when the run is no longer in {@code from}.
     */
    private void claim(TransferRunEntity run, Collection<TransferState> from, TransferState to) {
        int max = settings.intValue(TransferSettings.MAX_CONCURRENT_RUNS);
        if (runs.countByStateIn(TransferState.ACTIVE) >= max) {
            throw new ConflictException(
                    "transfer-concurrency-limit",
                    "%d transfers are already running, the most allowed at once (transfer.max-concurrent-runs)."
                                    .formatted(max)
                            + " Wait for one to finish.");
        }
        try {
            if (runs.transition(run.getId(), from, to) == 0) {
                throw new ConflictException(
                        "transfer-run-state", "This transfer changed state meanwhile. Reload it and try again.");
            }
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "transfer-run-in-progress",
                    "Another transfer is running from queue %s. Wait for it to finish, or stop it."
                            .formatted(run.getSourceQueue()));
        }
    }

    /** The segment's audit event on the source cluster, and its linked event on the target cluster. */
    private void beginAudit(TransferRunEntity run, Operator operator, String action, Map<String, ?> params) {
        AuditEvent source = audit.begin(
                operator.actor(),
                action,
                "QUEUE",
                run.getSourceQueue(),
                run.getSourceClusterId(),
                run.getSourceNodeId(),
                params,
                false);
        AuditEvent target = childOf(
                source.getId(),
                () -> audit.begin(
                        operator.actor(),
                        action + ".in",
                        "QUEUE",
                        run.getTargetQueue(),
                        run.getTargetClusterId(),
                        run.getTargetNodeId(),
                        params,
                        false));
        run.attachAudit(source.getId(), target.getId());
    }

    private static <T> T childOf(Long parent, ScopedValue.CallableOp<T, RuntimeException> action) {
        return parent == null
                ? action.call()
                : ScopedValue.where(AuditScope.PARENT, parent).call(action);
    }

    private Map<String, Object> auditParams(TransferRunEntity run) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", run.getId().toString());
        params.put("mode", run.getMode().name());
        params.put("source", run.getSourceNodeName() + "/" + run.getSourceQueue());
        params.put("target", run.getTargetNodeName() + "/" + run.getTargetQueue());
        params.put("targetClusterId", run.getTargetClusterId().toString());
        params.put("selection", selection(run));
        params.put("t0", run.getT0().toString());
        params.put("estimate", run.getEstimate());
        params.put("overrideCap", run.isOverrideCap());
        return params;
    }

    // ---- read ---------------------------------------------------------------

    public TransferRunView get(UUID clusterId, UUID runId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        TransferRunEntity run = load(clusterId, runId);
        requireReadable(run);
        return view(run);
    }

    /** Runs where this cluster is the source or the target, newest first; a run whose other cluster the operator cannot read is left out. */
    public List<TransferRunView> history(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return runs.history(clusterId, Limit.of(HISTORY)).stream()
                .filter(r -> permissions.can(r.getSourceClusterId(), Permissions.CLUSTER_READ)
                        && permissions.can(r.getTargetClusterId(), Permissions.CLUSTER_READ))
                .map(this::view)
                .toList();
    }

    /** Previews nobody executed; runs are kept. Run daily by {@code TransferJobs}. */
    public void deleteExpiredPreviews() {
        runs.deleteExpired(TransferState.PREVIEWED, Instant.now());
    }

    // ---- orphaned staging ------------------------------------------------------

    /** Staging queues on this cluster's live nodes that no run knows: left by a run whose record is gone. */
    public List<OrphanView> orphans(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<OrphanView> out = new ArrayList<>();
        for (ClusterNode node : ServingNodes.from(directory.nodes(clusterId))) {
            if (!TransferNodes.live(node)) {
                continue;
            }
            try {
                JolokiaBrokerClient client = nodes.client(node);
                for (String queue : staging.list(client)) {
                    UUID runId = runIdOf(queue);
                    if (runId != null && runs.existsById(runId)) {
                        continue;
                    }
                    out.add(new OrphanView(node.getId(), node.getName(), queue, depth(client, queue)));
                }
            } catch (BrokerConnectionException e) {
                // A node that cannot be read shows none; its orphans are still there for the next look.
            }
        }
        return out;
    }

    /** Put an orphaned staging queue's messages on a queue of the same node, then remove the staging queue. */
    public OrphanReturnView returnOrphan(UUID clusterId, OrphanReturnRequest request) {
        access.requireCluster(clusterId, MessagePermissions.MESSAGE_MOVE);
        ClusterNode node = nodes.node(clusterId, request.nodeId());
        String queue = request.stagingQueue();
        if (!queue.startsWith(StagingQueues.PREFIX)) {
            throw new IllegalArgumentException(queue + " is not a Studio staging queue.");
        }
        UUID runId = runIdOf(queue);
        if (runId != null && runs.existsById(runId)) {
            throw new ConflictException(
                    "transfer-not-orphaned",
                    "Staging queue %s belongs to transfer %s. Resume or return that run instead."
                            .formatted(queue, runId));
        }
        Operator operator = handoff.capture();
        AuditEvent event = audit.begin(
                operator.actor(),
                AUDIT_ACTION + ".orphan-return",
                "QUEUE",
                queue,
                clusterId,
                node.getId(),
                Map.of("stagingQueue", queue, "target", request.targetQueue()),
                false);
        long returned = 0;
        try {
            JolokiaBrokerClient client = nodes.client(node);
            String mbean = BrokerMBeans.queue(client.resolveBrokerObjectName(), queue, queue, "ANYCAST");
            int chunk = settings.intValue(TransferSettings.BATCH_SIZE);
            long moved;
            do {
                moved = messages.moveMessages(client, mbean, chunk, "", request.targetQueue(), false, chunk);
                returned += moved;
            } while (moved >= chunk);
            boolean removed = staging.destroyIfEmpty(client, queue);
            Long left = removed ? Long.valueOf(0) : depth(client, queue);
            long remaining = left == null ? 0 : left;
            if (removed) {
                audit.succeed(event, returned);
            } else {
                audit.failPartial(
                        event,
                        returned,
                        "%d messages are still in %s (in delivery or scheduled); it was kept."
                                .formatted(remaining, queue));
            }
            return new OrphanReturnView(queue, returned, remaining, removed);
        } catch (RuntimeException e) {
            if (returned > 0) {
                audit.failPartial(event, returned, e.getMessage());
            } else {
                audit.fail(event, e.getMessage());
            }
            throw e;
        }
    }

    private Long depth(JolokiaBrokerClient client, String queue) {
        try {
            return messages.messageCount(
                    client, BrokerMBeans.queue(client.resolveBrokerObjectName(), queue, queue, "ANYCAST"));
        } catch (BrokerConnectionException e) {
            return null;
        }
    }

    private static UUID runIdOf(String stagingQueue) {
        try {
            return UUID.fromString(stagingQueue.substring(StagingQueues.PREFIX.length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    // ---- plumbing -------------------------------------------------------------

    /** A run this cluster is the source or target of; 404 otherwise. */
    private TransferRunEntity load(UUID clusterId, UUID runId) {
        return runs.findById(runId)
                .filter(r -> r.getSourceClusterId().equals(clusterId)
                        || r.getTargetClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("transfer", runId));
    }

    private TransferRunEntity reload(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("transfer", runId));
    }

    /** Acting on a run needs its mode's permission on the source and {@code message:send} on the target. */
    private void requireRunPermissions(TransferRunEntity run) {
        access.requireCluster(run.getSourceClusterId(), run.getMode().sourcePermission());
        access.requireCluster(run.getTargetClusterId(), MessagePermissions.MESSAGE_SEND);
    }

    private void requireReadable(TransferRunEntity run) {
        access.requireCluster(run.getSourceClusterId(), Permissions.CLUSTER_READ);
        access.requireCluster(run.getTargetClusterId(), Permissions.CLUSTER_READ);
    }

    List<Finding> findings(TransferRunEntity run) {
        return json.readValue(run.getFindings(), new TypeReference<List<Finding>>() {});
    }

    TransferSelection selection(TransferRunEntity run) {
        return json.readValue(run.getSelection(), TransferSelection.class);
    }

    TransferRunView view(TransferRunEntity run) {
        TransferSelection selection = selection(run);
        boolean staged = run.getMode() == TransferMode.MOVE && !run.isSameNode();
        long held =
                staged ? Math.max(0, run.getStaged() - run.getDelivered() - run.getExpired() - run.getReturned()) : 0;
        Double rate = null;
        if (run.getState().active() && run.getStartedAt() != null) {
            double seconds = Duration.between(run.getStartedAt(), Instant.now()).toMillis() / 1000.0;
            rate = seconds > 0 ? run.getDelivered() / seconds : null;
        }
        List<String> notes = new ArrayList<>();
        notes.add("Messages published over AMQP, MQTT, STOMP or OpenWire arrive in their Core form.");
        if (selection.kind() != SelectionKind.IDS) {
            notes.add("Only messages timestamped at or before %s are selected, so messages produced during the run"
                            .formatted(run.getT0())
                    + " stay; a message whose producer's clock runs ahead is left out too.");
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        return views.toView(
                run,
                new TransferEnd(
                        run.getSourceClusterId(),
                        run.getSourceNodeId(),
                        run.getSourceNodeName(),
                        run.getSourceQueue(),
                        run.getSourceAddress()),
                new TransferEnd(
                        run.getTargetClusterId(),
                        run.getTargetNodeId(),
                        run.getTargetNodeName(),
                        run.getTargetQueue(),
                        run.getTargetAddress()),
                selection,
                findings(run),
                new Derived(
                        notes,
                        held,
                        rate,
                        staged ? StagingQueues.queueName(run.getId()) : null,
                        cap,
                        run.getEstimate() != null && run.getEstimate() > cap,
                        run.getState().resumable(),
                        run.getState().resumable() && staged));
    }
}
