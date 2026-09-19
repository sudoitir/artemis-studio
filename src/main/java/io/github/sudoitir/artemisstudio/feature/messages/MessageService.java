package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.MessageDetailView;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.MessagePageView;
import io.github.sudoitir.artemisstudio.feature.messages.web.MessageViews.MessageSummaryView;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.platform.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.CoreSubscriptionManager;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaMessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.platform.broker.MessageOperations;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.SendSpec;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.governance.ClearViewAudit;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.GovernContext;
import io.github.sudoitir.artemisstudio.platform.governance.GovernanceViews;
import io.github.sudoitir.artemisstudio.platform.governance.GovernedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.MessageContent;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshot;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * cached {@code queue_snapshot} row, never the client. Every broker request waits for
 * the node's rate ceiling in the transport itself (non-negotiable #1, ADR-0076); every mutation writes
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

    private final QueueSnapshots queueSnapshots;
    private final ClusterDirectory brokerNodes;
    private final BrokerConnections connections;
    private final MessageOperations messageOps;
    private final JolokiaMessageTransport jolokiaTransport;
    private final CoreMessageTransport coreTransport;
    private final CoreSubscriptionManager subscriptions;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final SseHub sseHub;
    private final ClusterAccessGuard clusterAccess;
    private final ContentPolicy contentPolicy;
    private final ClearViewAudit clearViews;

    /** Resolved (node, address, routingType) for a queue name on a cluster. */
    record ResolvedQueue(ClusterNode node, String address, String routingType) {}

    /** A mutation result: an executed affected-count, or a point-in-time dry-run estimate. */
    public sealed interface Outcome {
        UUID node();

        record Affected(long count, UUID node) implements Outcome {}

        record DryRun(long count, long cap, boolean overCap, UUID node) implements Outcome {}

        /**
         * An operation by ids that stopped part-way: {@code count} were acted on, and
         * {@code notDone} holds every id that was not — refused, or never sent after {@code error}.
         */
        record Partial(long count, List<Long> notDone, String error, UUID node) implements Outcome {}
    }

    // ---- browse -----------------------------------------------------------

    @Transactional(readOnly = true)
    public MessagePageView browse(UUID clusterId, String queueName, UUID nodeId, String filter, int page, int size) {
        clusterAccess.requireCluster(clusterId, MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowseResult result = browseAt(clusterId, queueName, resolved, page, Math.min(size, BROKER_PAGE_CAP), filter);
        GovernContext context = contentPolicy.context(clusterId, resolved.address());
        List<BrowsedMessage> messages = result.page().messages();
        List<GovernedMessage> governed = messages.stream()
                .map(m -> contentPolicy.govern(context, content(m)))
                .toList();
        List<MessageSummaryView> rows = new java.util.ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            rows.add(toSummary(messages.get(i), governed.get(i)));
        }
        clearViews.record(context, "QUEUE", queueName, governed);
        return new MessagePageView(
                rows,
                result.page().total(),
                result.page().totalUnavailable(),
                page,
                size,
                resolved.node().getId(),
                result.servedBy().name());
    }

    @Transactional(readOnly = true)
    public MessageDetailView detail(UUID clusterId, String queueName, long messageId, UUID nodeId, String filter) {
        clusterAccess.requireCluster(clusterId, MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowseResult result = browseAt(clusterId, queueName, resolved, 1, BROKER_PAGE_CAP, filter);
        BrowsedMessage message = result.page().messages().stream()
                .filter(m -> m.messageId() == messageId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("message", messageId));
        GovernContext context = contentPolicy.context(clusterId, resolved.address());
        GovernedMessage governed = contentPolicy.govern(context, content(message));
        clearViews.record(context, "MESSAGE", queueName + "/" + messageId, List.of(governed));
        return toDetail(
                message, governed, resolved.node().getId(), result.servedBy().name());
    }

    // ---- send (Slice 4) -------------------------------------------------

    public Attempt<Outcome> send(
            UUID clusterId, String queueName, UUID nodeId, SendMessageRequest req, boolean dryRun) {
        clusterAccess.requireCluster(clusterId, MessagePermissions.MESSAGE_SEND);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        AuditEvent event = begin(
                "SEND_MESSAGE", queueName, clusterId, resolved.node().getId(), Map.of("type", req.type()), dryRun);
        if (dryRun) {
            audit.succeed(event, 1);
            return new Attempt.Ok<>(new Outcome.DryRun(
                    1,
                    settings.intValue(BrokerSettings.BULK_CAP),
                    false,
                    resolved.node().getId()));
        }
        try {
            transportFor(clusterId)
                    .send(
                            targetOf(clusterId, queueName, resolved),
                            new SendSpec(
                                    req.type(),
                                    req.durable(),
                                    req.body(),
                                    req.bodyBase64(),
                                    req.headers(),
                                    req.properties()));
            audit.succeed(event, 1);
            publishQueuesAfterCommit(clusterId);
            return new Attempt.Ok<>(new Outcome.Affected(1, resolved.node().getId()));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    // ---- move / retry / delete / expire (Slices 5 + 6) ----------------

    public Attempt<Outcome> execute(
            UUID clusterId,
            String queueName,
            UUID nodeId,
            MessageAction action,
            MessageActionRequest req,
            boolean dryRun,
            boolean override) {
        clusterAccess.requireCluster(clusterId, permissionFor(action));
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
        AuditEvent event = begin(action.auditName(), queueName, clusterId, node, params, dryRun);

        if (action == MessageAction.MOVE
                && (req.targetQueue() == null || req.targetQueue().isBlank())) {
            audit.fail(event, "MOVE requires a target queue.");
            throw new IllegalArgumentException("MOVE requires a target queue.");
        }
        // Artemis has no by-filter retry — RETRY is by explicit id, or "retry all"
        // (the DLQ replay). A filter on a RETRY is ignored, not an error.
        boolean retryAll = action == MessageAction.RETRY && req.ids().isEmpty();

        long cap = settings.intValue(BrokerSettings.BULK_CAP);

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

            if (idBased) {
                MessageOperations.BulkResult result = performByIds(client, mbean, action, req);
                publishQueuesAfterCommit(clusterId);
                if (result.partial()) {
                    // Reported as partial, never as a plain failure: some messages already moved.
                    audit.failPartial(
                            event,
                            result.affected(),
                            "Stopped after " + result.affected() + " of "
                                    + req.ids().size() + ": " + result.error());
                    return new Attempt.Ok<>(
                            new Outcome.Partial(result.affected(), result.notDone(), result.error(), node));
                }
                audit.succeed(event, result.affected());
                return new Attempt.Ok<>(new Outcome.Affected(result.affected(), node));
            }
            long affected = perform(client, mbean, action, req);
            audit.succeed(event, affected);
            publishQueuesAfterCommit(clusterId);
            return new Attempt.Ok<>(new Outcome.Affected(affected, node));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // A filter the broker rejected. The row is closed as failed rather than left pending.
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    // ---- purge (Slice 7) ---------------------------------------------

    public Attempt<Outcome> purge(UUID clusterId, String queueName, UUID nodeId, boolean dryRun, boolean override) {
        clusterAccess.requireCluster(clusterId, MessagePermissions.QUEUE_PURGE);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();
        AuditEvent event = begin("PURGE_QUEUE", queueName, clusterId, node, Map.of(), dryRun);
        try {
            JolokiaBrokerClient client = clientFor(clusterId, resolved);
            String mbean = queueMbean(client, resolved, queueName);
            long cap = settings.intValue(BrokerSettings.BULK_CAP);

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

    private static String permissionFor(MessageAction action) {
        return switch (action) {
            case MOVE, RETRY -> MessagePermissions.MESSAGE_MOVE;
            case DELETE, EXPIRE -> MessagePermissions.MESSAGE_DELETE;
        };
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

    /** A retry-all or a by-filter operation: one broker call, which returns its own count. */
    private long perform(JolokiaBrokerClient client, String mbean, MessageAction action, MessageActionRequest req) {
        if (action == MessageAction.RETRY && req.ids().isEmpty()) {
            return messageOps.retryAll(client, mbean);
        }
        return switch (action) {
            case MOVE -> messageOps.moveByFilter(client, mbean, req.filter(), req.targetQueue());
            case DELETE -> messageOps.deleteByFilter(client, mbean, req.filter());
            case EXPIRE -> messageOps.expireByFilter(client, mbean, req.filter());
            case RETRY -> throw new IllegalStateException("unreachable");
        };
    }

    private MessageOperations.BulkResult performByIds(
            JolokiaBrokerClient client, String mbean, MessageAction action, MessageActionRequest req) {
        List<Long> ids = req.ids();
        return switch (action) {
            case MOVE -> messageOps.moveByIds(client, mbean, ids, req.targetQueue());
            case RETRY -> messageOps.retryByIds(client, mbean, ids);
            case DELETE -> messageOps.deleteByIds(client, mbean, ids);
            case EXPIRE -> messageOps.expireByIds(client, mbean, ids);
        };
    }

    // ---- resolution + plumbing ------------------------------------

    private BrowseResult browseAt(
            UUID clusterId, String queueName, ResolvedQueue resolved, int page, int size, String filter) {
        return transportFor(clusterId).browse(targetOf(clusterId, queueName, resolved), page, size, filter);
    }

    /** Core when the cluster has a live Core subscription (ADR-0029, D-honesty); Jolokia otherwise. */
    private MessageTransport transportFor(UUID clusterId) {
        return subscriptions.verdictFor(clusterId).isConnected() ? coreTransport : jolokiaTransport;
    }

    private static TransportTarget targetOf(UUID clusterId, String queueName, ResolvedQueue resolved) {
        ClusterNode node = resolved.node();
        return new TransportTarget(
                clusterId,
                node.getId(),
                queueName,
                resolved.address(),
                resolved.routingType(),
                node.getJolokiaUrl(),
                node.getCoreUrl());
    }

    private JolokiaBrokerClient clientFor(UUID clusterId, ResolvedQueue resolved) {
        return connections.forCluster(clusterId, resolved.node().getJolokiaUrl());
    }

    private static String queueMbean(JolokiaBrokerClient client, ResolvedQueue resolved, String queueName) {
        return BrokerMBeans.queue(
                client.resolveBrokerObjectName(), resolved.address(), queueName, resolved.routingType());
    }

    private AuditEvent begin(
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

    ResolvedQueue resolve(UUID clusterId, String queueName, UUID nodeId) {
        List<QueueSnapshot> snapshots = queueSnapshots.forCluster(clusterId).stream()
                .filter(s -> s.queueName().equals(queueName))
                .toList();
        if (snapshots.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        QueueSnapshot any = snapshots.get(0);

        Map<UUID, ClusterNode> byId = brokerNodes.nodes(clusterId).stream()
                .collect(Collectors.toMap(ClusterNode::getId, Function.identity()));
        // Every live node of a cluster holds its own copy of the queue, so "the live node" is
        // not one node. Unasked, open the copy holding the most messages: the first live node
        // listed can hold none of them, and the view then reads as an empty queue.
        List<ClusterNode> candidates = snapshots.stream()
                .sorted(Comparator.comparingLong(QueueSnapshot::messageCount).reversed())
                .map(s -> byId.get(s.nodeId()))
                .filter(n -> n != null && n.getJolokiaUrl() != null)
                .toList();
        if (candidates.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "No manageable node holds queue '" + queueName + "'.");
        }

        ClusterNode chosen;
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
        return new ResolvedQueue(chosen, any.address(), any.routingType());
    }

    /**
     * The broker's message in the policy's neutral shape: its identifying headers and every property.
     * Shared with the SQL console, which governs the same messages.
     */
    public static MessageContent content(BrowsedMessage m) {
        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", m.correlationId());
        headers.put("groupId", m.groupId());
        headers.put("userId", m.userId());
        headers.put("replyTo", m.replyTo());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(m.stringProperties());
        properties.putAll(m.intProperties());
        properties.putAll(m.longProperties());
        properties.putAll(m.doubleProperties());
        properties.putAll(m.booleanProperties());
        return new MessageContent(
                headers, properties, m.body(), m.bodyEncoding() == MessageBrowser.BodyEncoding.BASE64, m.contentType());
    }

    private static MessageSummaryView toSummary(BrowsedMessage m, GovernedMessage g) {
        String body = g.body();
        return new MessageSummaryView(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                g.headers().get("groupId"),
                g.headers().get("correlationId"),
                body == null ? null : body.length() <= 200 ? body : body.substring(0, 200),
                m.bodyTruncated(),
                m.propertyCount(),
                GovernanceViews.redactions(g));
    }

    private static MessageDetailView toDetail(BrowsedMessage m, GovernedMessage g, UUID node, String transport) {
        // A masked value is a marker string whatever its original type, so it is listed with the strings.
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        g.properties().forEach((name, value) -> {
            if (value instanceof Long l && m.intProperties().containsKey(name)) {
                ints.put(name, l);
            } else if (value instanceof Long l && m.longProperties().containsKey(name)) {
                longs.put(name, l);
            } else if (value instanceof Double d) {
                doubles.put(name, d);
            } else if (value instanceof Boolean b) {
                booleans.put(name, b);
            } else {
                strings.put(name, String.valueOf(value));
            }
        });
        return new MessageDetailView(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                g.headers().get("groupId"),
                g.headers().get("correlationId"),
                g.headers().get("userId"),
                g.body(),
                m.bodyEncoding().name(),
                m.contentType(),
                m.bodyTruncated(),
                m.observedLimitBytes(),
                transport,
                node,
                strings,
                ints,
                longs,
                doubles,
                booleans,
                GovernanceViews.redactions(g),
                GovernanceViews.withheld(g));
    }
}
