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
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
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
import io.github.sudoitir.artemisstudio.platform.broker.SendNames;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.governance.ClearViewAudit;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.GovernContext;
import io.github.sudoitir.artemisstudio.platform.governance.GovernanceViews;
import io.github.sudoitir.artemisstudio.platform.governance.GovernedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.MessageContent;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueLocator.QueueLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * cached {@code queue_snapshot} row, or a live read for a queue the scrape has not reached,
 * never the client. Every broker request waits for
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

    private static final String CORRELATION_ID = "correlationId";
    private static final String GROUP_ID = "groupId";

    private final QueueLocator queueLocator;
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
    public MessagePageView browse(
            UUID clusterId, String queueName, UUID nodeId, String filter, int page, int requestedSize) {
        clusterAccess.requireResource(clusterId, ResourceRef.queue(queueName), MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        // The broker serves at most BROKER_PAGE_CAP rows; the page size reported and used for hasNext is that one.
        int size = Math.min(requestedSize, BROKER_PAGE_CAP);
        BrowseResult result = browseAt(clusterId, queueName, resolved, page, size, filter);
        GovernContext context = contentPolicy.context(clusterId, resolved.address(), queueName);
        List<BrowsedMessage> messages = result.page().messages();
        List<GovernedMessage> governed = messages.stream()
                .map(m -> contentPolicy.govern(context, content(m)))
                .toList();
        List<MessageSummaryView> rows = new java.util.ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            rows.add(toSummary(messages.get(i), governed.get(i)));
        }
        clearViews.recordClear(context, "QUEUE", queueName, governed);
        Long total = result.page().total();
        // ponytail: with no broker count, a full page is taken to have a successor; the last page can be a false yes
        boolean hasNext = total != null ? (long) page * size < total : rows.size() == size;
        return new MessagePageView(
                rows,
                page,
                size,
                total,
                hasNext,
                result.page().totalUnavailable(),
                resolved.node().getId(),
                result.servedBy().name());
    }

    @Transactional(readOnly = true)
    public MessageDetailView detail(UUID clusterId, String queueName, long messageId, UUID nodeId, String filter) {
        clusterAccess.requireResource(clusterId, ResourceRef.queue(queueName), MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowseResult result = browseAt(clusterId, queueName, resolved, 1, BROKER_PAGE_CAP, filter);
        BrowsedMessage message = result.page().messages().stream()
                .filter(m -> m.messageId() == messageId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("message", messageId));
        GovernContext context = contentPolicy.context(clusterId, resolved.address(), queueName);
        GovernedMessage governed = contentPolicy.govern(context, content(message));
        clearViews.recordClear(context, "MESSAGE", queueName + "/" + messageId, List.of(governed));
        return toDetail(
                message, governed, resolved.node().getId(), result.servedBy().name());
    }

    // ---- send (Slice 4) -------------------------------------------------

    public Attempt<Outcome> send(
            UUID clusterId, String queueName, UUID nodeId, SendMessageRequest req, boolean dryRun) {
        // A message is sent to the address the queue is bound to; a queue whose address the caller may not
        // use is, to them, a queue that is not there.
        clusterAccess.requireResource(clusterId, ResourceRef.queue(queueName), Permissions.QUEUE_READ);
        SendNames.validate(req.headers(), req.properties());
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        try {
            clusterAccess.requireResource(
                    clusterId, ResourceRef.address(resolved.address()), MessagePermissions.MESSAGE_SEND);
        } catch (NotFoundException _) {
            throw new NotFoundException("queue", queueName);
        }
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
        requireAllowed(clusterId, queueName, action, req);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();
        Set<String> checkedOrigins = null;
        if (action == MessageAction.RETRY) {
            try {
                checkedOrigins = requireOriginalAddresses(clusterId, queueName, resolved);
            } catch (BrokerConnectionException e) {
                return new Attempt.Failed<>(e.kind(), e.getMessage());
            }
        }

        AuditEvent event = begin(action.auditName(), queueName, clusterId, node, auditParams(req), dryRun);

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
                return executeByIds(event, client, mbean, action, req, clusterId, node, checkedOrigins);
            }
            long affected = perform(client, mbean, action, req, checkedOrigins);
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

    private static Map<String, Object> auditParams(MessageActionRequest req) {
        Map<String, Object> params = new HashMap<>();
        if (req.byFilter()) {
            params.put("filter", req.filter());
        } else {
            params.put("ids", req.ids().size());
        }
        if (req.targetQueue() != null) {
            params.put("target", req.targetQueue());
        }
        return params;
    }

    private Attempt<Outcome> executeByIds(
            AuditEvent event,
            JolokiaBrokerClient client,
            String mbean,
            MessageAction action,
            MessageActionRequest req,
            UUID clusterId,
            UUID node,
            Set<String> checkedOrigins) {
        MessageOperations.BulkResult result = performByIds(client, mbean, action, req, checkedOrigins);
        publishQueuesAfterCommit(clusterId);
        if (result.partial()) {
            // Reported as partial, never as a plain failure: some messages already moved.
            audit.failPartial(
                    event,
                    result.affected(),
                    "Stopped after " + result.affected() + " of " + req.ids().size() + ": " + result.error());
            return new Attempt.Ok<>(new Outcome.Partial(result.affected(), result.notDone(), result.error(), node));
        }
        audit.succeed(event, result.affected());
        return new Attempt.Ok<>(new Outcome.Affected(result.affected(), node));
    }

    // ---- purge (Slice 7) ---------------------------------------------

    public Attempt<Outcome> purge(UUID clusterId, String queueName, UUID nodeId, boolean dryRun, boolean override) {
        clusterAccess.requireResource(clusterId, ResourceRef.queue(queueName), MessagePermissions.QUEUE_PURGE);
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

    /**
     * What the action needs, on every resource it touches, checked before anything is done: the action's
     * permission on the queue, and for a move also {@code message:send} on the address of the queue the
     * messages go to.
     */
    private void requireAllowed(UUID clusterId, String queueName, MessageAction action, MessageActionRequest req) {
        List<Requirement> needs = new java.util.ArrayList<>();
        needs.add(new Requirement(ResourceRef.queue(queueName), permissionFor(action)));
        if (action == MessageAction.MOVE
                && req.targetQueue() != null
                && !req.targetQueue().isBlank()) {
            // Messages go into the target queue, so it is read and sent to through the address it is bound to.
            needs.add(new Requirement(ResourceRef.queue(req.targetQueue()), Permissions.QUEUE_READ));
            needs.add(new Requirement(
                    ResourceRef.address(addressOf(clusterId, req.targetQueue())), MessagePermissions.MESSAGE_SEND));
        }
        clusterAccess.requireAll(clusterId, needs);
    }

    /**
     * The address the queue is bound to, found on the scrape or the live nodes. A queue no node has is refused
     * as one the caller may not read is, never guessed from its name.
     */
    private String addressOf(UUID clusterId, String queueName) {
        clusterAccess.requireVisible(clusterId);
        return queueLocator.locate(clusterId, queueName).stream()
                .findFirst()
                .map(QueueLocation::address)
                .orElseThrow(() -> clusterAccess.unreadableAmongSeveral(clusterId));
    }

    /**
     * A retry sends each message back to the address it came from, so the caller needs
     * {@code message:send} on every address the queue's messages came from, unless they hold it on the
     * whole cluster. It is the queue's whole content that is checked, not only the messages chosen. The
     * addresses checked are returned (null when no check was needed), for the retry to keep to.
     */
    private Set<String> requireOriginalAddresses(UUID clusterId, String queueName, ResolvedQueue resolved) {
        if (clusterAccess.holds(clusterId, MessagePermissions.MESSAGE_SEND)) {
            return null;
        }
        JolokiaBrokerClient client = clientFor(clusterId, resolved);
        Set<String> origins = messageOps.originalAddresses(client, queueMbean(client, resolved, queueName));
        clusterAccess.requireAll(
                clusterId,
                origins.stream()
                        .map(a -> new Requirement(ResourceRef.address(a), MessagePermissions.MESSAGE_SEND))
                        .toList());
        return origins;
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
    private long perform(
            JolokiaBrokerClient client,
            String mbean,
            MessageAction action,
            MessageActionRequest req,
            Set<String> checkedOrigins) {
        if (action == MessageAction.RETRY && req.ids().isEmpty()) {
            return checkedOrigins == null
                    ? messageOps.retryAll(client, mbean)
                    : retryFromCheckedOrigins(client, mbean, checkedOrigins);
        }
        return switch (action) {
            case MOVE -> messageOps.moveByFilter(client, mbean, req.filter(), req.targetQueue());
            case DELETE -> messageOps.deleteByFilter(client, mbean, req.filter());
            case EXPIRE -> messageOps.expireByFilter(client, mbean, req.filter());
            case RETRY -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * A retry sends each message back to the address it came from, which the caller was checked against
     * a moment ago. Retrying the whole queue would also retry what arrived since, so the messages to
     * retry are fixed now, by the checked addresses, and retried by id: a message that came from another
     * address in the meantime is not among them.
     */
    private long retryFromCheckedOrigins(JolokiaBrokerClient client, String mbean, Set<String> checkedOrigins) {
        List<Long> ids = new ArrayList<>();
        for (String origin : checkedOrigins) {
            ids.addAll(messageOps.listIds(client, mbean, messageOps.originalAddressFilter(origin)));
        }
        return ids.isEmpty() ? 0 : messageOps.retryByIds(client, mbean, ids).affected();
    }

    private MessageOperations.BulkResult performByIds(
            JolokiaBrokerClient client,
            String mbean,
            MessageAction action,
            MessageActionRequest req,
            Set<String> checkedOrigins) {
        List<Long> ids = req.ids();
        if (action == MessageAction.RETRY && checkedOrigins != null) {
            // Only messages that came from the addresses checked: an id of a message that arrived since, from
            // another address, is not retried.
            Set<Long> fromChecked = new HashSet<>();
            for (String origin : checkedOrigins) {
                fromChecked.addAll(messageOps.listIds(client, mbean, messageOps.originalAddressFilter(origin)));
            }
            ids = ids.stream().filter(fromChecked::contains).toList();
        }
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
        List<QueueLocation> locations = queueLocator.locate(clusterId, queueName);
        if (locations.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        QueueLocation any = locations.get(0);

        Map<UUID, ClusterNode> byId = brokerNodes.nodes(clusterId).stream()
                .collect(Collectors.toMap(ClusterNode::getId, Function.identity()));
        // Every live node of a cluster holds its own copy of the queue, so "the live node" is
        // not one node. Unasked, open the copy holding the most messages: the first live node
        // listed can hold none of them, and the view then reads as an empty queue.
        List<ClusterNode> candidates = locations.stream()
                .sorted(Comparator.comparingLong(QueueLocation::messageCount).reversed())
                .map(l -> byId.get(l.nodeId()))
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
        headers.put(CORRELATION_ID, m.correlationId());
        headers.put(GROUP_ID, m.groupId());
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
                g.headers().get(GROUP_ID),
                g.headers().get(CORRELATION_ID),
                preview(body),
                m.bodyTruncated(),
                m.propertyCount(),
                GovernanceViews.redactions(g));
    }

    private static String preview(String body) {
        if (body == null || body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200);
    }

    private static MessageDetailView toDetail(BrowsedMessage m, GovernedMessage g, UUID node, String transport) {
        // A masked value is a marker string whatever its original type, so it is listed with the strings.
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        g.properties().forEach((name, value) -> {
            switch (value) {
                case Long l when m.intProperties().containsKey(name) -> ints.put(name, l);
                case Long l when m.longProperties().containsKey(name) -> longs.put(name, l);
                case Double d -> doubles.put(name, d);
                case Boolean b -> booleans.put(name, b);
                case null, default -> strings.put(name, String.valueOf(value));
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
                g.headers().get(GROUP_ID),
                g.headers().get(CORRELATION_ID),
                g.headers().get("userId"),
                g.body(),
                m.bodyEncoding().name(),
                m.bodyCompression().apiName(),
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
