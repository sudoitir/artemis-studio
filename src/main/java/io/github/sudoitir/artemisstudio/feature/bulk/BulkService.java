package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunMapper.ItemEstimate;
import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunMapper.RunOptions;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkRunView;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkSelection;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.NodeFigure;
import io.github.sudoitir.artemisstudio.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.QueueNodeCell;
import io.github.sudoitir.artemisstudio.feature.resources.web.ResourceViews.QueueView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk runs (ADR-0093): preview a frozen set of queues, execute it one queue at a time through the
 * single-queue commands, stop it, and read it back.
 */
@Service
@RequiredArgsConstructor
public class BulkService {

    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);
    private static final int HISTORY = 100;

    private final BulkRunRepository runs;
    private final BulkRunItemRepository items;
    private final BulkRunMapper views;
    private final BulkRunner runner;
    private final CrossNodeAggregator queues;
    private final ClusterAccessGuard clusterAccess;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SettingsService settings;
    private final ObjectMapper json;

    // ---- preview -----------------------------------------------------------

    /**
     * Resolve and freeze the set, and state its blast radius from the aggregated queue snapshot: no
     * broker call per queue (ADR-0093 D2). The authoritative preflight still runs per queue on execute.
     * Writes no audit event: nothing is done.
     */
    public BulkRunDetailView preview(UUID clusterId, BulkPreviewRequest request) {
        BulkOperation operation = Objects.requireNonNull(request.operation(), "operation");
        clusterAccess.requireCluster(clusterId, operation.permission());
        Map<String, QueueView> byName = new LinkedHashMap<>();
        queues.allQueues(clusterId).forEach(q -> byName.putIfAbsent(q.queueName(), q));

        List<String> names = resolve(request, byName);
        int queueCap = settings.intValue(BrokerSettings.BULK_QUEUE_CAP);
        if (names.isEmpty()) {
            throw new BulkRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "bulk-nothing-selected", "No queue matches this selection.");
        }
        if (names.size() > queueCap) {
            throw new BulkRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "bulk-queue-cap-exceeded",
                    "%d queues matched; a bulk run is capped at %d queues (safety.bulk-queue-cap). Narrow the selection."
                            .formatted(names.size(), queueCap));
        }

        List<Planned> plan = names.stream()
                .map(name -> plan(operation, request.disconnectConsumers(), name, byName.get(name)))
                .toList();
        long estimate = 0;
        boolean complete = true;
        for (Planned p : plan) {
            if (p.refusal() != null || !operation.destructive()) {
                continue;
            }
            for (NodeFigure n : p.figures().nodes()) {
                if (n.messageCount() == null) {
                    complete = false;
                } else {
                    estimate += n.messageCount();
                }
            }
        }

        RunOptions options = new RunOptions(request.disconnectConsumers());
        Instant now = Instant.now();
        BulkRunEntity run = runs.save(new BulkRunEntity(
                clusterId,
                operation,
                handoff.capture().actor().displayName(),
                planHash(operation, options, names),
                json.writeValueAsString(new BulkSelection(request.names() == null ? null : names, request.q())),
                json.writeValueAsString(options),
                names.size(),
                (int) plan.stream().filter(p -> p.refusal() != null).count(),
                estimate,
                complete,
                now,
                now.plus(PREVIEW_LIFETIME)));
        List<BulkRunItemEntity> rows = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Planned p = plan.get(i);
            rows.add(
                    new BulkRunItemEntity(run.getId(), i, p.name(), p.refusal(), json.writeValueAsString(p.figures())));
        }
        items.saveAll(rows);
        return detail(run);
    }

    private record Planned(String name, String refusal, ItemEstimate figures) {}

    private static List<String> resolve(BulkPreviewRequest request, Map<String, QueueView> byName) {
        if (request.names() != null) {
            return List.copyOf(new LinkedHashSet<>(request.names()));
        }
        ResourceQuery query = ResourceQuery.of(request.q(), 1, 1, null);
        return byName.values().stream()
                .filter(q -> query.matches(q.queueName()) || query.matches(q.address()))
                .map(QueueView::queueName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** One queue's figures, refusal and warning, by the rules the single-queue preflight applies. */
    private static Planned plan(BulkOperation operation, boolean disconnectConsumers, String name, QueueView queue) {
        if (queue == null) {
            return new Planned(
                    name, "No queue with this name exists on the cluster.", new ItemEstimate(List.of(), null));
        }
        List<NodeFigure> nodes =
                queue.perNode().stream().map(BulkService::figure).toList();
        List<String> warnings = new ArrayList<>();
        List<String> unknown = nodes.stream()
                .filter(n -> n.messageCount() == null)
                .map(NodeFigure::nodeName)
                .toList();
        if (!unknown.isEmpty()) {
            warnings.add("Node %s has not answered recently, so this queue's figures there are unknown."
                    .formatted(String.join(", ", unknown)));
        }
        long consumers = nodes.stream()
                .map(NodeFigure::consumerCount)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        String refusal = null;
        switch (operation) {
            case DELETE -> {
                if (consumers > 0 && !disconnectConsumers) {
                    refusal =
                            "%d consumers are attached. A delete refuses a queue with consumers unless they are disconnected."
                                    .formatted(consumers);
                } else if (consumers > 0) {
                    warnings.add("%d consumers will be disconnected.".formatted(consumers));
                }
            }
            case PAUSE -> {
                if (!nodes.isEmpty() && nodes.stream().allMatch(NodeFigure::paused)) {
                    warnings.add("Already paused; nothing changes.");
                }
            }
            case RESUME -> {
                if (nodes.stream().noneMatch(NodeFigure::paused)) {
                    warnings.add("Not paused; nothing changes.");
                }
            }
            case PURGE -> {}
        }
        return new Planned(
                name, refusal, new ItemEstimate(nodes, warnings.isEmpty() ? null : String.join(" ", warnings)));
    }

    /** A node that has not answered within the freshness window has unknown figures, never zero. */
    private static NodeFigure figure(QueueNodeCell cell) {
        return new NodeFigure(
                cell.nodeId(),
                cell.nodeName(),
                cell.stale() ? null : cell.messageCount(),
                cell.stale() ? null : cell.consumerCount(),
                cell.paused());
    }

    /** SHA-256 over what the run will do: the operation, the options that change it, the queues in order. */
    static String planHash(BulkOperation operation, RunOptions options, List<String> names) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String plan = operation.name() + "\n" + options.disconnectConsumers() + "\n" + String.join("\n", names);
            return HexFormat.of().formatHex(sha.digest(plan.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- execute and stop -------------------------------------------------

    /**
     * Start the previewed run in the background and return at once. Refused when the plan is not the
     * one previewed, the preview expired or already ran, a destructive estimate is over the cap
     * without the override, or another run is executing on the cluster.
     */
    public BulkRunView execute(UUID clusterId, UUID runId, BulkExecuteRequest request) {
        BulkRunEntity run = load(clusterId, runId);
        BulkOperation operation = run.getOperation();
        clusterAccess.requireCluster(clusterId, operation.permission());
        if (run.getStatus() != BulkRunStatus.PREVIEWED) {
            throw new ConflictException(
                    "bulk-run-started", "This bulk run has already been executed. Preview again to run it again.");
        }
        if (!run.getPlanHash().equals(request.planHash())) {
            throw new ConflictException(
                    "bulk-plan-mismatch",
                    "This is not the plan that was previewed. Preview again and confirm what it shows.");
        }
        if (Instant.now().isAfter(run.getExpiresAt())) {
            throw new BulkRefusedException(
                    HttpStatus.GONE,
                    "bulk-preview-expired",
                    "This preview expired at %s; queues may have changed since. Preview again."
                            .formatted(run.getExpiresAt()));
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        if (operation.destructive() && run.getEstimate() > cap && !request.override()) {
            throw new BulkCapExceededException(run.getEstimate(), cap);
        }

        Operator operator = handoff.capture();
        try {
            // Claimed once: a second execute of the same preview finds it no longer PREVIEWED.
            if (runs.transition(runId, BulkRunStatus.PREVIEWED, BulkRunStatus.RUNNING) == 0) {
                throw new ConflictException(
                        "bulk-run-started", "This bulk run has already been executed. Preview again to run it again.");
            }
        } catch (DataIntegrityViolationException e) {
            String running = runs.findFirstByClusterIdAndStatus(clusterId, BulkRunStatus.RUNNING)
                    .map(r -> r.getId().toString())
                    .orElse("another run");
            throw new ConflictException(
                    "bulk-run-in-progress",
                    "Bulk run " + running + " is executing on this cluster. Wait for it to finish, or stop it.");
        }
        run.start(operator.actor().displayName(), request.override(), request.continueOnFailure(), Instant.now());
        AuditEvent event = audit.begin(
                operator.actor(),
                operation.auditAction(),
                "QUEUE",
                run.getTotalItems() + " queues",
                clusterId,
                null,
                auditParams(run),
                false);
        run.attachAudit(event.getId());
        run = runs.save(run);
        runner.start(run, event, operator);
        return view(run);
    }

    private Map<String, Object> auditParams(BulkRunEntity run) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", run.getId().toString());
        params.put("queues", run.getTotalItems());
        params.put("selection", json.readValue(run.getSelection(), BulkSelection.class));
        params.put("estimate", run.getEstimate());
        params.put("estimateComplete", run.isEstimateComplete());
        params.put("overrideCap", run.isOverrideCap());
        params.put("continueOnFailure", run.isContinueOnFailure());
        params.put("disconnectConsumers", options(run).disconnectConsumers());
        return params;
    }

    /** Ask a running run to stop: the queue in flight finishes, the rest are cancelled. */
    public BulkRunView stop(UUID clusterId, UUID runId) {
        BulkRunEntity run = load(clusterId, runId);
        clusterAccess.requireCluster(clusterId, run.getOperation().permission());
        if (!runner.requestStop(runId)) {
            throw new ConflictException(
                    "bulk-run-not-running", "This bulk run is not executing, so there is nothing to stop.");
        }
        return view(run);
    }

    // ---- read --------------------------------------------------------------

    public BulkRunDetailView get(UUID clusterId, UUID runId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return detail(load(clusterId, runId));
    }

    /** Runs that were executed, newest first. */
    public List<BulkRunView> history(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return runs
                .findByClusterIdAndStatusNotOrderByCreatedAtDesc(clusterId, BulkRunStatus.PREVIEWED, Limit.of(HISTORY))
                .stream()
                .map(this::view)
                .toList();
    }

    /** Previews nobody executed; finished runs are kept. Run daily by {@code BulkJobs}. */
    public void deleteExpiredPreviews() {
        runs.deleteExpired(BulkRunStatus.PREVIEWED, Instant.now());
    }

    private BulkRunEntity load(UUID clusterId, UUID runId) {
        return runs.findByIdAndClusterId(runId, clusterId).orElseThrow(() -> new NotFoundException("bulk run", runId));
    }

    private BulkRunDetailView detail(BulkRunEntity run) {
        List<BulkItemView> rows = items.findByRunIdOrderByOrdinal(run.getId()).stream()
                .map(this::view)
                .toList();
        return new BulkRunDetailView(view(run), rows);
    }

    private BulkRunView view(BulkRunEntity run) {
        return views.toView(
                run,
                settings.intValue(BrokerSettings.BULK_CAP),
                json.readValue(run.getSelection(), BulkSelection.class),
                options(run));
    }

    private BulkItemView view(BulkRunItemEntity item) {
        List<NodeOutcome> outcome = item.getOutcome() == null
                ? null
                : json.readValue(item.getOutcome(), new TypeReference<List<NodeOutcome>>() {});
        return views.toView(item, json.readValue(item.getEstimate(), ItemEstimate.class), outcome);
    }

    private RunOptions options(BulkRunEntity run) {
        return json.readValue(run.getOptions(), RunOptions.class);
    }
}
