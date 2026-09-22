package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunMapper.ItemEstimate;
import io.github.sudoitir.artemisstudio.feature.bulk.BulkRunMapper.RunOptions;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkProgress;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.NodeFigure;
import io.github.sudoitir.artemisstudio.feature.messages.MessageService;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleService;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditScope;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.artemisstudio.platform.clusters.LifecycleOutcome.NodeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes a bulk run as a {@link BackgroundRuns} run (ADR-0093 D4): one queue at a time, in preview
 * order, each through the single-queue command as the operator who executed the run and under the
 * run's audit event. Nothing here re-implements a safety check; the commands carry them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BulkRunner {

    static final String TOPIC = "bulk";

    private final BulkRunRepository runs;
    private final BulkRunItemRepository items;
    private final QueueLifecycleService queues;
    private final MessageService messages;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SseHub sse;
    private final ObjectMapper json;
    private final BackgroundRuns background;

    void start(BulkRunEntity run, AuditEvent event, Operator operator) {
        background.start(run.getId(), operator, () -> execute(run, event, operator));
    }

    /** False when the run is not executing in this process. */
    boolean requestStop(UUID runId) {
        return background.requestStop(runId);
    }

    private void execute(BulkRunEntity run, AuditEvent event, Operator operator) {
        BooleanSupplier stop = () -> background.stopRequested(run.getId());
        List<BulkRunItemEntity> rows = items.findByRunIdOrderByOrdinal(run.getId());
        String error = null;
        try {
            boolean halted = false;
            for (BulkRunItemEntity item : rows) {
                if (item.getStatus() != BulkItemStatus.PENDING) {
                    continue;
                }
                if (stop.getAsBoolean() || halted) {
                    item.finish(
                            stop.getAsBoolean() ? BulkItemStatus.CANCELLED : BulkItemStatus.SKIPPED,
                            null,
                            null,
                            null,
                            Instant.now());
                    items.save(item);
                    continue;
                }
                actOn(run, item, event, operator);
                progress(run, rows, BulkRunStatus.RUNNING);
                boolean bad = item.getStatus() == BulkItemStatus.FAILED || item.getStatus() == BulkItemStatus.PARTIAL;
                halted = bad && !run.isContinueOnFailure();
            }
        } catch (RuntimeException e) {
            // A failure outside any one queue's command, such as the database going away.
            log.error("Bulk run {} stopped unexpectedly", run.getId(), e);
            error = "The run stopped unexpectedly: " + e.getMessage();
        } finally {
            finish(run, rows, event, stop.getAsBoolean(), error);
        }
    }

    private void actOn(BulkRunEntity run, BulkRunItemEntity item, AuditEvent event, Operator operator) {
        item.begin(Instant.now());
        items.save(item);
        LifecycleOutcome[] result = new LifecycleOutcome[1];
        String[] failure = new String[1];
        String permission = run.getOperation().permission();
        if (!handoff.stillHolds(operator, run.getClusterId(), permission)) {
            failure[0] = "The permission %s on this cluster is no longer held, so this queue was not acted on."
                    .formatted(permission);
        } else {
            handoff.runAs(
                    operator,
                    () -> ScopedValue.where(AuditScope.PARENT, event.getId()).run(() -> {
                        try {
                            result[0] = command(run, item);
                        } catch (RuntimeException e) {
                            // One queue's failure is that queue's outcome, never the run's death.
                            failure[0] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        }
                    }));
        }
        if (result[0] == null) {
            item.finish(BulkItemStatus.FAILED, failure[0], null, null, Instant.now());
        } else {
            List<NodeOutcome> nodes = result[0].nodes();
            item.finish(
                    classify(nodes),
                    failureSummary(nodes),
                    result[0].totalAffected(),
                    json.writeValueAsString(nodes),
                    Instant.now());
        }
        items.save(item);
    }

    /** The single-queue command for this operation, with the run's override and options. */
    private LifecycleOutcome command(BulkRunEntity run, BulkRunItemEntity item) {
        UUID clusterId = run.getClusterId();
        String queue = item.getQueueName();
        return switch (run.getOperation()) {
            case PAUSE -> unwrap(queues.setPaused(clusterId, queue, true, false));
            case RESUME -> unwrap(queues.setPaused(clusterId, queue, false, false));
            case DELETE ->
                unwrap(queues.deleteQueue(
                        clusterId,
                        queue,
                        false,
                        run.isOverrideCap(),
                        json.readValue(run.getOptions(), RunOptions.class).disconnectConsumers()));
            case PURGE -> purge(run, item);
        };
    }

    /** A purge is one node per call, so it is issued on every node hosting the queue and folded into one outcome. */
    private LifecycleOutcome purge(BulkRunEntity run, BulkRunItemEntity item) {
        List<NodeOutcome> nodes = new ArrayList<>();
        for (NodeFigure node :
                json.readValue(item.getEstimate(), ItemEstimate.class).nodes()) {
            NodeOutcome outcome;
            try {
                outcome = switch (messages.purge(
                        run.getClusterId(), item.getQueueName(), node.nodeId(), false, run.isOverrideCap())) {
                    case Attempt.Ok<MessageService.Outcome>(MessageService.Outcome.Affected affected) ->
                        new NodeOutcome(node.nodeId(), node.nodeName(), NodeStatus.APPLIED, affected.count(), null);
                    case Attempt.Ok<MessageService.Outcome> other ->
                        NodeOutcome.failed(node.nodeId(), node.nodeName(), "Unexpected purge result: " + other.value());
                    case Attempt.Failed<MessageService.Outcome> failed ->
                        NodeOutcome.failed(node.nodeId(), node.nodeName(), failed.detail());
                };
            } catch (RuntimeException e) {
                outcome = NodeOutcome.failed(node.nodeId(), node.nodeName(), e.getMessage());
            }
            nodes.add(outcome);
        }
        return new LifecycleOutcome(false, 0, false, nodes);
    }

    private static LifecycleOutcome unwrap(Attempt<LifecycleOutcome> attempt) {
        return switch (attempt) {
            case Attempt.Ok<LifecycleOutcome> ok -> ok.value();
            case Attempt.Failed<LifecycleOutcome> failed -> throw new IllegalStateException(failed.detail());
        };
    }

    /** Every node that acted applied or was already there: succeeded; some failed: partial; none acted: failed. */
    static BulkItemStatus classify(List<NodeOutcome> nodes) {
        long ok = nodes.stream()
                .filter(n -> n.status() == NodeStatus.APPLIED || n.status() == NodeStatus.ALREADY)
                .count();
        long bad = nodes.stream().filter(n -> n.status() == NodeStatus.FAILED).count();
        if (ok > 0 && bad == 0) {
            return BulkItemStatus.SUCCEEDED;
        }
        return ok > 0 ? BulkItemStatus.PARTIAL : BulkItemStatus.FAILED;
    }

    private static String failureSummary(List<NodeOutcome> nodes) {
        List<String> failed = nodes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .toList();
        if (!failed.isEmpty()) {
            return String.join(" | ", failed);
        }
        return nodes.stream().anyMatch(n -> n.status() != NodeStatus.SKIPPED_NOT_LIVE)
                ? null
                : "No node hosting this queue was live, so it was not acted on.";
    }

    private void progress(BulkRunEntity run, List<BulkRunItemEntity> rows, BulkRunStatus status) {
        int succeeded =
                (int) rows.stream().filter(i -> i.getStatus().succeeded()).count();
        int failed = (int) rows.stream().filter(i -> i.getStatus().failed()).count();
        int skipped = (int) rows.stream().filter(i -> i.getStatus().skipped()).count();
        run.count(succeeded, failed, skipped);
        runs.save(run);
        sse.publish(
                run.getClusterId(),
                TOPIC,
                new BulkProgress(run.getId(), status, succeeded, failed, skipped, run.getTotalItems()),
                null);
    }

    /** Always reached: the run gets a terminal status and its audit event an outcome, whatever happened. */
    private void finish(
            BulkRunEntity run, List<BulkRunItemEntity> rows, AuditEvent event, boolean stopped, String error) {
        Instant now = Instant.now();
        for (BulkRunItemEntity item : rows) {
            // Only after an unexpected failure can an item be left unfinished.
            if (item.getStatus() == BulkItemStatus.PENDING || item.getStatus() == BulkItemStatus.RUNNING) {
                item.finish(
                        item.getStatus() == BulkItemStatus.RUNNING ? BulkItemStatus.UNKNOWN : BulkItemStatus.CANCELLED,
                        item.getStatus() == BulkItemStatus.RUNNING ? error : null,
                        null,
                        null,
                        now);
                items.save(item);
            }
        }
        long succeeded = rows.stream().filter(i -> i.getStatus().succeeded()).count();
        BulkRunStatus status = stopped && rows.stream().anyMatch(i -> i.getStatus() == BulkItemStatus.CANCELLED)
                ? BulkRunStatus.STOPPED
                : succeeded == rows.size()
                        ? BulkRunStatus.SUCCEEDED
                        : succeeded == 0 ? BulkRunStatus.FAILED : BulkRunStatus.PARTIAL;
        try {
            run.finish(status, error, now);
            progress(run, rows, status);
        } finally {
            long affected = rows.stream()
                    .map(BulkRunItemEntity::getAffected)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
            String summary = status == BulkRunStatus.SUCCEEDED
                    ? null
                    : Objects.requireNonNullElse(
                            error, "%s: %d of %d queues succeeded.".formatted(status, succeeded, rows.size()));
            audit.finish(
                    event,
                    status != BulkRunStatus.SUCCEEDED,
                    affected,
                    summary,
                    Map.of("runId", run.getId().toString(), "status", status.name()));
        }
    }
}
