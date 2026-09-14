package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The flow module's disposable caches (ADR-0081): the observation lease, the latest aggregated
 * client edges and each node's sampling coverage. JDBC batch upserts, as for {@code queue_snapshot}
 * (ADR-0016): rows rewritten every sweep have no identity worth dirty-checking.
 */
@Repository
@RequiredArgsConstructor
public class FlowStore {

    /** One node's outcome of one sweep. {@code errorKind} is null when the node answered in full. */
    public record NodeSample(
            UUID nodeId,
            UUID clusterId,
            Instant sampledAt,
            int producersSeen,
            int producersTotal,
            int consumersSeen,
            int consumersTotal,
            String error,
            String errorKind) {}

    /** A persisted client edge as a reader sees it. */
    public record StoredEdge(UUID nodeId, Instant sampledAt, Edge edge) {}

    private static final String UPSERT_EDGE = """
            INSERT INTO flow_client_edge
              (sampled_at, rate, unacked, member_count, kind, client_id, user_name, remote_host,
               protocol, address, queue_name, node_id, cluster_id, stalled)
            VALUES
              (:sampledAt, :rate, :unacked, :memberCount, :kind, :clientId, :userName, :remoteHost,
               :protocol, :address, :queueName, :nodeId, :clusterId, :stalled)
            ON CONFLICT (node_id, kind, client_id, user_name, remote_host, protocol, address, queue_name) DO UPDATE SET
               sampled_at   = EXCLUDED.sampled_at,
               rate         = EXCLUDED.rate,
               unacked      = EXCLUDED.unacked,
               member_count = EXCLUDED.member_count,
               cluster_id   = EXCLUDED.cluster_id,
               stalled      = EXCLUDED.stalled
            """;

    private static final String UPSERT_NODE = """
            INSERT INTO flow_node_sample
              (sampled_at, producers_seen, producers_total, consumers_seen, consumers_total,
               error, error_kind, node_id, cluster_id)
            VALUES
              (:sampledAt, :producersSeen, :producersTotal, :consumersSeen, :consumersTotal,
               :error, :errorKind, :nodeId, :clusterId)
            ON CONFLICT (node_id) DO UPDATE SET
               sampled_at      = EXCLUDED.sampled_at,
               producers_seen  = EXCLUDED.producers_seen,
               producers_total = EXCLUDED.producers_total,
               consumers_seen  = EXCLUDED.consumers_seen,
               consumers_total = EXCLUDED.consumers_total,
               error           = EXCLUDED.error,
               error_kind      = EXCLUDED.error_kind,
               cluster_id      = EXCLUDED.cluster_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Extend a cluster's observation lease to at least {@code until}; never shortens it. */
    public void renew(UUID clusterId, Instant until) {
        jdbc.update("""
                INSERT INTO flow_demand (observed_until, cluster_id) VALUES (:until, :clusterId)
                ON CONFLICT (cluster_id) DO UPDATE
                   SET observed_until = GREATEST(flow_demand.observed_until, EXCLUDED.observed_until)
                """, new MapSqlParameterSource("until", Timestamp.from(until)).addValue("clusterId", clusterId));
    }

    /** Clusters whose lease has not expired at {@code now}, whichever instance renewed it. */
    public Set<UUID> observedClusters(Instant now) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT cluster_id FROM flow_demand WHERE observed_until > :now",
                Map.of("now", Timestamp.from(now)),
                UUID.class));
    }

    /**
     * Replace one node's edges with this sweep's and record its coverage, in one short transaction
     * after the broker calls have returned. A node that failed passes no edges, so its clients stop
     * being shown instead of being shown stale.
     */
    @Transactional
    public void persistNode(NodeSample sample, List<Edge> edges) {
        if (!edges.isEmpty()) {
            SqlParameterSource[] rows =
                    edges.stream().map(e -> edgeParams(sample, e)).toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate(UPSERT_EDGE, rows);
        }
        jdbc.update(
                "DELETE FROM flow_client_edge WHERE node_id = :nodeId AND sampled_at < :sampledAt",
                new MapSqlParameterSource("nodeId", sample.nodeId())
                        .addValue("sampledAt", Timestamp.from(sample.sampledAt())));
        jdbc.update(UPSERT_NODE, nodeParams(sample));
    }

    /** Drop the caches of clusters nobody has observed since {@code before}, lease included. */
    @Transactional
    public void forgetUnobserved(Instant before) {
        MapSqlParameterSource p = new MapSqlParameterSource("before", Timestamp.from(before));
        String stale = "SELECT cluster_id FROM flow_demand WHERE observed_until < :before";
        jdbc.update("DELETE FROM flow_client_edge WHERE cluster_id IN (" + stale + ")", p);
        jdbc.update("DELETE FROM flow_node_sample WHERE cluster_id IN (" + stale + ")", p);
        jdbc.update("DELETE FROM flow_demand WHERE observed_until < :before", p);
    }

    public List<StoredEdge> edges(UUID clusterId) {
        return jdbc.query(
                "SELECT * FROM flow_client_edge WHERE cluster_id = :clusterId",
                Map.of("clusterId", clusterId),
                (rs, i) -> new StoredEdge(
                        rs.getObject("node_id", UUID.class),
                        rs.getTimestamp("sampled_at").toInstant(),
                        edge(rs)));
    }

    public List<NodeSample> nodeSamples(UUID clusterId) {
        return jdbc.query(
                "SELECT * FROM flow_node_sample WHERE cluster_id = :clusterId",
                Map.of("clusterId", clusterId),
                (rs, i) -> new NodeSample(
                        rs.getObject("node_id", UUID.class),
                        rs.getObject("cluster_id", UUID.class),
                        rs.getTimestamp("sampled_at").toInstant(),
                        rs.getInt("producers_seen"),
                        rs.getInt("producers_total"),
                        rs.getInt("consumers_seen"),
                        rs.getInt("consumers_total"),
                        rs.getString("error"),
                        rs.getString("error_kind")));
    }

    private static Edge edge(ResultSet rs) throws SQLException {
        // getObject, not getDouble + wasNull: wasNull reports the last column read, and the
        // constructor arguments below read several after this one.
        Double rate = rs.getObject("rate", Double.class);
        return new Edge(
                Kind.valueOf(rs.getString("kind")),
                rs.getString("client_id"),
                rs.getString("user_name"),
                rs.getString("remote_host"),
                rs.getString("protocol"),
                rs.getString("address"),
                rs.getString("queue_name"),
                rate,
                rs.getLong("unacked"),
                rs.getInt("member_count"),
                rs.getBoolean("stalled"));
    }

    private static SqlParameterSource edgeParams(NodeSample s, Edge e) {
        return new MapSqlParameterSource()
                .addValue("sampledAt", Timestamp.from(s.sampledAt()))
                .addValue("rate", e.rate())
                .addValue("unacked", e.unacked())
                .addValue("memberCount", e.memberCount())
                .addValue("kind", e.kind().name())
                .addValue("clientId", e.clientId())
                .addValue("userName", e.user())
                .addValue("remoteHost", e.remoteHost())
                .addValue("protocol", e.protocol())
                .addValue("address", e.address())
                .addValue("queueName", e.queue())
                .addValue("nodeId", s.nodeId())
                .addValue("clusterId", s.clusterId())
                .addValue("stalled", e.stalled());
    }

    private static SqlParameterSource nodeParams(NodeSample s) {
        return new MapSqlParameterSource()
                .addValue("sampledAt", Timestamp.from(s.sampledAt()))
                .addValue("producersSeen", s.producersSeen())
                .addValue("producersTotal", s.producersTotal())
                .addValue("consumersSeen", s.consumersSeen())
                .addValue("consumersTotal", s.consumersTotal())
                .addValue("error", s.error())
                .addValue("errorKind", s.errorKind())
                .addValue("nodeId", s.nodeId())
                .addValue("clusterId", s.clusterId());
    }
}
