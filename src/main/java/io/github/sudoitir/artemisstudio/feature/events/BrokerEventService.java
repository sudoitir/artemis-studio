package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventPageView;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.artemisstudio.kernel.core.Cursor;
import io.github.sudoitir.artemisstudio.kernel.core.HeavyRequests;
import io.github.sudoitir.artemisstudio.kernel.core.HistoryPage;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.ReadRouting;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.core.Routed;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceFilter;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Read side of the broker-event history (ADR-0028): filtered, paged, newest
 * first, with the current dropped-event count and the oldest retained event so
 * overflow is visible in the UI.
 */
@Service
@RequiredArgsConstructor
public class BrokerEventService {

    private static final TypeReference<Map<String, Object>> PROPS_TYPE = new TypeReference<>() {};

    private final BrokerEventRepository events;
    private final BrokerEventWriter writer;
    private final ObjectMapper mapper;

    /**
     * Guards {@link #page} and {@link #get} only. {@link #since} is reached solely from
     * {@code StreamController}, which applies the same check before subscribing.
     */
    private final ClusterAccessGuard clusterAccess;

    private final PermissionResolver permissions;

    private final ActorResolver actors;
    private final HeavyRequests heavy;
    private final ReadRouting routing;

    /** A page of the history: a few at once per user, the page size at most, and the database's 30 s. */
    static final HeavyRequests.Endpoint HISTORY =
            new HeavyRequests.Endpoint("event history reads", 4, Duration.ofSeconds(30), ResourceQuery.MAX_SIZE);

    private static final String COLUMNS = """
            seq, occurred_at, received_at, type, address, routing_name, consumer_name, session_name, connection_name,
            remote_address, username, node_id, props::text AS props""";

    /** Notification props carry filter strings and user-supplied names; the detectors run over them. */
    private final ContentPolicy contentPolicy;

    /**
     * A page of the cluster's events, newest first. By page number for the first {@value HistoryPage#MAX_OFFSET} rows,
     * with a total; by {@code query.after()} beyond that, which costs the same however deep it reads and has no total.
     * Read from the replica when there is one and it is current.
     */
    public BrokerEventPageView page(UUID clusterId, BrokerEventQuery query) {
        clusterAccess.requireVisible(clusterId);
        ResourceQuery paging = ResourceQuery.ofPage(query.page(), query.size());
        if (query.after() == null) {
            HistoryPage.requireReachable(paging);
        }
        ResourceFilter addresses = permissions.filter(clusterId, ResourceKind.ADDRESS);
        // Filtered in the query, before paging, so a total never counts an event about an address the caller
        // may not read. An event about no address belongs to the cluster, and is read with it.
        List<String> readable = addresses.everything()
                ? List.of("")
                : events.findDistinctAddressByClusterId(clusterId).stream()
                        .filter(addresses::readable)
                        .toList();
        Where where = new Where(
                clusterId,
                addresses.everything(),
                readable.isEmpty() ? List.of("") : readable,
                addresses.everything() ? List.of("") : readableRoutingNames(clusterId, addresses),
                clusterAccess.holds(clusterId, Permissions.CLUSTER_READ),
                blankToNull(query.type()),
                query.nodeId(),
                blankToNull(query.address()),
                query.from() != null ? query.from() : Instant.EPOCH,
                query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z"));
        Routed<Slice> routed = heavy.run(
                HISTORY,
                actors.resolve().displayName(),
                run -> run.read(routing, client -> slice(client, where, query, paging)));
        Slice slice = routed.value();
        boolean more = slice.rows().size() > paging.size();
        List<BrokerEventView> shown = more ? slice.rows().subList(0, paging.size()) : slice.rows();
        return new BrokerEventPageView(
                shown,
                paging.page(),
                paging.size(),
                slice.count(),
                more,
                more ? new Cursor(shown.getLast().seq(), 0).encode() : null,
                routed.fromReplica(),
                writer.droppedFor(clusterId),
                slice.oldestRetained());
    }

    /** What a read keeps. */
    private record Where(
            UUID clusterId,
            boolean everything,
            List<String> readableAddresses,
            List<String> readableNames,
            boolean unaddressed,
            String type,
            UUID nodeId,
            String address,
            Instant from,
            Instant to) {}

    private record Slice(List<BrokerEventView> rows, Long count, Instant oldestRetained) {}

    /** One more row than the page holds, so there is a next page when it comes back; and the total, by page number. */
    private Slice slice(JdbcClient client, Where w, BrokerEventQuery query, ResourceQuery paging) {
        StringBuilder conditions =
                new StringBuilder("cluster_id = :cluster AND occurred_at >= :from AND occurred_at <= :to");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cluster", w.clusterId());
        params.put("from", Timestamp.from(w.from()));
        params.put("to", Timestamp.from(w.to()));
        if (!w.everything()) {
            conditions
                    .append(
                            w.unaddressed()
                                    ? " AND (address IN (:addresses) OR address IS NULL)"
                                    : " AND address IN (:addresses)")
                    .append(" AND (routing_name IS NULL OR routing_name IN (:names))");
            params.put("addresses", w.readableAddresses());
            params.put("names", w.readableNames());
        }
        optional(conditions, params, "type", w.type());
        optional(conditions, params, "node_id", w.nodeId());
        optional(conditions, params, "address", w.address());
        Cursor after = query.after();
        String sql = "SELECT " + COLUMNS + " FROM broker_event WHERE " + conditions
                + (after == null ? "" : " AND seq < :afterSeq")
                + " ORDER BY seq DESC LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec spec = client.sql(sql)
                .params(params)
                .param("limit", paging.size() + 1)
                .param("offset", after == null ? paging.offset() : 0);
        if (after != null) {
            spec = spec.param("afterSeq", after.major());
        }
        List<BrokerEventView> rows = spec.query(this::row).list();
        Long count = after != null
                ? null
                : client.sql("SELECT count(*) FROM broker_event WHERE " + conditions)
                        .params(params)
                        .query(Long.class)
                        .single();
        Instant oldest = client.sql("SELECT min(occurred_at) FROM broker_event WHERE cluster_id = :cluster")
                .param("cluster", w.clusterId())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
        return new Slice(rows, count, oldest);
    }

    private static void optional(StringBuilder sql, Map<String, Object> params, String column, Object value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(column);
            params.put(column, value);
        }
    }

    private BrokerEventView row(ResultSet rs, int i) throws SQLException {
        return new BrokerEventView(
                rs.getLong("seq"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getString("type"),
                rs.getString("address"),
                rs.getString("routing_name"),
                rs.getString("consumer_name"),
                rs.getString("session_name"),
                rs.getString("connection_name"),
                rs.getString("remote_address"),
                rs.getString("username"),
                rs.getObject("node_id", UUID.class),
                parseProps(rs.getString("props")));
    }

    /**
     * An event also names the queue a binding or a consumer concerns, and is left out for a reader who may not
     * read that queue, as it would name it. A routing name that is no queue of theirs is such a name too.
     */
    private List<String> readableRoutingNames(UUID clusterId, ResourceFilter addresses) {
        ResourceFilter queues = permissions.filter(clusterId, ResourceKind.QUEUE);
        List<String> names = events.findDistinctRoutingNameByClusterId(clusterId).stream()
                .filter(name -> queues.readable(name) || addresses.readable(name))
                .toList();
        return names.isEmpty() ? List.of("") : names;
    }

    private boolean namesOnlyWhatIsReadable(UUID clusterId, String routingName) {
        if (routingName == null) {
            return true;
        }
        return permissions.filter(clusterId, ResourceKind.QUEUE).readable(routingName)
                || permissions.filter(clusterId, ResourceKind.ADDRESS).readable(routingName);
    }

    @Transactional(readOnly = true)
    public BrokerEventView get(UUID clusterId, long seq) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceKind.ADDRESS);
        boolean clusterWide = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        return events.findByClusterIdAndSeq(clusterId, seq)
                .filter(e -> e.getAddress() == null ? clusterWide : addresses.readable(e.getAddress()))
                .filter(e -> addresses.everything() || namesOnlyWhatIsReadable(clusterId, e.getRoutingName()))
                .map(this::toView)
                .orElseThrow(() -> new NotFoundException("event", seq));
    }

    /**
     * Recent events of one type for a cluster, unguarded, for scheduled evaluation.
     *
     * <p>A scrape-driven job runs with no authenticated principal, so a permission check
     * there protects nothing and fails as a 404 about a cluster that plainly exists. The
     * guard stays on {@link #page}, which is the request path. Used by the consumer-health
     * verdict to find the broker's own {@code CONSUMER_SLOW} notifications (ADR-0089).
     *
     * <p><b>Never call this from a request path.</b>
     */
    @Transactional(readOnly = true)
    public List<BrokerEventView> recentOfType(UUID clusterId, String type, Instant from, Instant to, int limit) {
        return events
                .findPage(
                        clusterId,
                        true,
                        List.of(""),
                        List.of(""),
                        true,
                        blankToNull(type),
                        null,
                        null,
                        from != null ? from : Instant.EPOCH,
                        to != null ? to : Instant.parse("9999-12-31T23:59:59Z"),
                        PageRequest.of(0, Math.clamp(limit, 1, 500)))
                .getContent()
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * Bounded replay for a reconnecting SSE client: the {@code cap} most recent events after
     * {@code lastSeq}, oldest first, so the replay runs straight on into live delivery. A client
     * that missed more is told to resync by the stream.
     */
    @Transactional(readOnly = true)
    public List<BrokerEventView> since(UUID clusterId, long lastSeq, int cap) {
        return events
                .findByClusterIdAndSeqGreaterThanOrderBySeqDesc(
                        clusterId, lastSeq, PageRequest.of(0, Math.clamp(cap, 1, 1000)))
                .reversed()
                .stream()
                .map(this::toView)
                .toList();
    }

    public BrokerEventView toView(BrokerEventEntity e) {
        return new BrokerEventView(
                e.getSeq(),
                e.getOccurredAt(),
                e.getReceivedAt(),
                e.getType(),
                e.getAddress(),
                e.getRoutingName(),
                e.getConsumerName(),
                e.getSessionName(),
                e.getConnectionName(),
                e.getRemoteAddress(),
                e.getUsername(),
                e.getNodeId(),
                parseProps(e.getProps()));
    }

    private Map<String, Object> parseProps(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> props;
        try {
            props = mapper.readValue(json, PROPS_TYPE);
        } catch (RuntimeException _) {
            return Map.of();
        }
        Map<String, Object> governed = new LinkedHashMap<>();
        props.forEach((key, value) ->
                governed.put(key, value instanceof String text ? contentPolicy.governText(text) : value));
        return governed;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
