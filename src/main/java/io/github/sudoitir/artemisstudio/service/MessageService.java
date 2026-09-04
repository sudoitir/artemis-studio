package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageOperations;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotEntity;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.MessageActionRequest;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.SendMessageRequest;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.MessageDetailView;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.MessagePageView;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.MessageSummaryView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Message browse and the destructive message operations for one queue on one
 * node (ADR-0021, ADR-0022). {@code address} / {@code routingType} come from the
 * cached {@code queue_snapshot} row, never the client. Every broker call takes a
 * {@link NodeCallLimiter} permit first (non-negotiable #1); every mutation writes
 * an {@code audit_event} in its own transaction, before the broker call, updated
 * with the outcome (non-negotiable #3); a dry run is a broker-side estimate,
 * still audited with {@code dry_run = true}. A successful mutation nudges the SSE
 * {@code queues} topic after commit so the grid refreshes at once.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    /** {@code managementBrowsePageSize} default — the broker will not return more per page. */
    static final int BROKER_PAGE_CAP = 200;

    private final QueueSnapshotRepository queueSnapshots;
    private final BrokerNodeRepository brokerNodes;
    private final BrokerConnections connections;
    private final MessageBrowser messageBrowser;
    private final MessageOperations messageOps;
    private final NodeCallLimiter limiter;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final SseHub sseHub;

    /** Resolved (node, address, routingType) for a queue name on a cluster. */
    record ResolvedQueue(BrokerNodeEntity node, String address, String routingType) {}

    /** A mutation result: an executed affected-count, or a point-in-time dry-run estimate. */
    public sealed interface Outcome {
        UUID node();

        record Affected(long count, UUID node) implements Outcome {}

        record DryRun(long count, long cap, boolean overCap, UUID node) implements Outcome {}
    }

    // ---- browse -----------------------------------------------------------

    @Transactional(readOnly = true)
    public MessagePageView browse(UUID clusterId, String queueName, UUID nodeId, String filter, int page, int size) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowsePage result = browseAt(clusterId, queueName, resolved, page, Math.min(size, BROKER_PAGE_CAP), filter);
        List<MessageSummaryView> rows =
                result.messages().stream().map(MessageService::toSummary).toList();
        return new MessagePageView(
                rows, result.total(), page, size, resolved.node().getId());
    }

    @Transactional(readOnly = true)
    public MessageDetailView detail(UUID clusterId, String queueName, long messageId, UUID nodeId, String filter) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowsePage result = browseAt(clusterId, queueName, resolved, 1, BROKER_PAGE_CAP, filter);
        return result.messages().stream()
                .filter(m -> m.messageId() == messageId)
                .findFirst()
                .map(m -> toDetail(m, resolved.node().getId()))
                .orElseThrow(() -> new NotFoundException("message", messageId));
    }

    // ---- send (Slice 4) -------------------------------------------------

    @Transactional
    public Attempt<Outcome> send(
            UUID clusterId, String queueName, UUID nodeId, SendMessageRequest req, boolean dryRun) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        AuditEventEntity event = begin(
                "SEND_MESSAGE", queueName, clusterId, resolved.node().getId(), Map.of("type", req.type()), dryRun);
        if (dryRun) {
            audit.succeed(event, 1);
            return new Attempt.Ok<>(new Outcome.DryRun(
                    1, settings.bulkCap(), false, resolved.node().getId()));
        }
        try {
            JolokiaBrokerClient client = clientFor(clusterId, resolved);
            String addressMbean = BrokerMBeans.address(client.resolveBrokerObjectName(), resolved.address());
            messageOps.send(client, addressMbean, mergeProps(req), req.type(), req.body(), req.durable());
            audit.succeed(event, 1);
            publishQueuesAfterCommit(clusterId);
            return new Attempt.Ok<>(new Outcome.Affected(1, resolved.node().getId()));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    // ---- move / retry / delete / expire (Slices 5 + 6) ----------------

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<Outcome> execute(
            UUID clusterId,
            String queueName,
            UUID nodeId,
            MessageAction action,
            MessageActionRequest req,
            boolean dryRun,
            boolean override) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();

        Map<String, Object> params = new HashMap<>();
        if (req.byFilter()) {
            params.put("filter", req.filter());
        } else {
            params.put("ids", req.ids().size());
        }
        if (req.targetQueue() != null) {
            params.put("target", req.targetQueue());
        }
        AuditEventEntity event = begin(action.auditName(), queueName, clusterId, node, params, dryRun);

        if (action == MessageAction.MOVE
                && (req.targetQueue() == null || req.targetQueue().isBlank())) {
            audit.fail(event, "MOVE requires a target queue.");
            throw new IllegalArgumentException("MOVE requires a target queue.");
        }
        // Artemis has no by-filter retry — RETRY is by explicit id, or "retry all"
        // (the DLQ replay). A filter on a RETRY is ignored, not an error.
        boolean retryAll = action == MessageAction.RETRY && req.ids().isEmpty();

        long cap = settings.bulkCap();

        // A by-id dry run needs no broker call at all — the estimate is the id count.
        boolean idBased = !retryAll && !req.byFilter() && !req.ids().isEmpty();
        if (dryRun && idBased) {
            long estimate = req.ids().size();
            audit.succeed(event, estimate);
            return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, estimate > cap, node));
        }

        try {
            JolokiaBrokerClient client = clientFor(clusterId, resolved);
            String mbean = queueMbean(client, resolved, queueName);
            long estimate = estimate(client, mbean, action, req);
            boolean over = estimate > cap;
            if (dryRun) {
                audit.succeed(event, estimate);
                return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, over, node));
            }
            if (over && !override) {
                audit.fail(event, "Over the safety cap (" + estimate + " > " + cap + ").");
                throw new BulkCapExceededException(estimate, cap);
            }

            long affected = perform(client, mbean, action, req);
            audit.succeed(event, affected);
            publishQueuesAfterCommit(clusterId);
            return new Attempt.Ok<>(new Outcome.Affected(affected, node));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    // ---- purge (Slice 7) ---------------------------------------------

    @Transactional(noRollbackFor = {BulkCapExceededException.class, IllegalArgumentException.class})
    public Attempt<Outcome> purge(UUID clusterId, String queueName, UUID nodeId, boolean dryRun, boolean override) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();
        AuditEventEntity event = begin("PURGE_QUEUE", queueName, clusterId, node, Map.of(), dryRun);
        try {
            JolokiaBrokerClient client = clientFor(clusterId, resolved);
            String mbean = queueMbean(client, resolved, queueName);
            long cap = settings.bulkCap();

            if (dryRun) {
                long estimate = messageOps.messageCount(client, mbean);
                audit.succeed(event, estimate);
                return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, estimate > cap, node));
            }

            long estimate = messageOps.messageCount(client, mbean);
            if (estimate > cap && !override) {
                audit.fail(event, "Over the safety cap (" + estimate + " > " + cap + ").");
                throw new BulkCapExceededException(estimate, cap);
            }
            long removed = messageOps.purge(client, mbean);
            audit.succeed(event, removed);
            publishQueuesAfterCommit(clusterId);
            return new Attempt.Ok<>(new Outcome.Affected(removed, node));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    // ---- estimate / perform dispatch --------------------------------

    private long estimate(JolokiaBrokerClient client, String mbean, MessageAction action, MessageActionRequest req) {
        if (action == MessageAction.RETRY && req.ids().isEmpty()) {
            return messageOps.messageCount(client, mbean);
        }
        if (req.byFilter()) {
            return messageOps.countMessages(client, mbean, req.filter());
        }
        return req.ids().size();
    }

    private long perform(JolokiaBrokerClient client, String mbean, MessageAction action, MessageActionRequest req) {
        List<Long> ids = req.ids();
        if (action == MessageAction.RETRY && ids.isEmpty()) {
            return messageOps.retryAll(client, mbean);
        }
        if (req.byFilter()) {
            return switch (action) {
                case MOVE -> messageOps.moveByFilter(client, mbean, req.filter(), req.targetQueue());
                case DELETE -> messageOps.deleteByFilter(client, mbean, req.filter());
                case EXPIRE -> messageOps.expireByFilter(client, mbean, req.filter());
                case RETRY -> throw new IllegalStateException("unreachable");
            };
        }
        return switch (action) {
            case MOVE -> messageOps.moveByIds(client, mbean, ids, req.targetQueue());
            case RETRY -> messageOps.retryByIds(client, mbean, ids);
            case DELETE -> messageOps.deleteByIds(client, mbean, ids);
            case EXPIRE -> messageOps.expireByIds(client, mbean, ids);
        };
    }

    // ---- resolution + plumbing ------------------------------------

    private BrowsePage browseAt(
            UUID clusterId, String queueName, ResolvedQueue resolved, int page, int size, String filter) {
        JolokiaBrokerClient client = clientFor(clusterId, resolved);
        return messageBrowser.browse(client, queueMbean(client, resolved, queueName), page, size, filter);
    }

    private JolokiaBrokerClient clientFor(UUID clusterId, ResolvedQueue resolved) {
        acquire(resolved.node().getId());
        return connections.forCluster(clusterId, resolved.node().getJolokiaUrl());
    }

    private static String queueMbean(JolokiaBrokerClient client, ResolvedQueue resolved, String queueName) {
        return BrokerMBeans.queue(
                client.resolveBrokerObjectName(), resolved.address(), queueName, resolved.routingType());
    }

    private AuditEventEntity begin(
            String action, String queueName, UUID clusterId, UUID node, Map<String, ?> params, boolean dryRun) {
        Actor actor = actorResolver.resolve();
        return audit.begin(actor, action, "QUEUE", queueName, clusterId, node, params, dryRun);
    }

    private void publishQueuesAfterCommit(UUID clusterId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseHub.publish(clusterId, "queues");
                }
            });
        } else {
            sseHub.publish(clusterId, "queues");
        }
    }

    private static Map<String, Object> mergeProps(SendMessageRequest req) {
        Map<String, Object> merged = new HashMap<>(req.headers());
        merged.putAll(req.properties());
        return merged;
    }

    ResolvedQueue resolve(UUID clusterId, String queueName, UUID nodeId) {
        List<QueueSnapshotEntity> snapshots = queueSnapshots.findByClusterId(clusterId).stream()
                .filter(s -> s.getQueueName().equals(queueName))
                .toList();
        if (snapshots.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        QueueSnapshotEntity any = snapshots.get(0);

        Map<UUID, BrokerNodeEntity> byId = brokerNodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .collect(Collectors.toMap(BrokerNodeEntity::getId, Function.identity()));
        List<BrokerNodeEntity> candidates = snapshots.stream()
                .map(s -> byId.get(s.getNodeId()))
                .filter(n -> n != null && n.getJolokiaUrl() != null)
                .toList();
        if (candidates.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "No manageable node holds queue '" + queueName + "'.");
        }

        BrokerNodeEntity chosen;
        if (nodeId != null) {
            chosen = candidates.stream()
                    .filter(n -> n.getId().equals(nodeId))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("node", nodeId));
        } else {
            chosen = candidates.stream()
                    .filter(n -> Boolean.TRUE.equals(n.getActive()))
                    .findFirst()
                    .orElse(candidates.get(0));
        }
        return new ResolvedQueue(chosen, any.getAddress(), any.getRoutingType());
    }

    private void acquire(UUID nodeId) {
        try {
            limiter.acquire(nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Timed out waiting for a per-node call permit.");
        }
    }

    private static MessageSummaryView toSummary(BrowsedMessage m) {
        return new MessageSummaryView(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                m.groupId(),
                m.correlationId(),
                m.bodyPreview(),
                m.bodyTruncated(),
                m.propertyCount());
    }

    private static MessageDetailView toDetail(BrowsedMessage m, UUID node) {
        return new MessageDetailView(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                m.groupId(),
                m.correlationId(),
                m.userId(),
                m.body(),
                m.bodyTruncated(),
                m.observedLimitBytes(),
                node,
                m.stringProperties(),
                m.intProperties(),
                m.longProperties(),
                m.doubleProperties(),
                m.booleanProperties());
    }
}
