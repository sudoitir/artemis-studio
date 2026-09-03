package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.QueueView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link CrossNodeAggregator} against a real Postgres, seeded through the real
 * upsert path: pair dedup, one-node vs two-node queues, and stale-not-dropped.
 */
class CrossNodeAggregatorTest extends PostgresIntegrationTest {

    @Autowired
    CrossNodeAggregator aggregator;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private record Fixture(UUID clusterId, UUID nodeA, UUID nodeB) {}

    /** A cluster with a live/backup pair (shared NodeID) plus a standalone node. */
    private Fixture pairPlusStandalone() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        String sharedNodeId = UUID.randomUUID().toString();
        BrokerNodeEntity live = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "live", "PRIMARY", sharedNodeId));
        BrokerNodeEntity backup = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "backup", "BACKUP", sharedNodeId));
        BrokerNodeEntity solo = nodes.save(BrokerNodeEntity.fromSeed(
                clusterId, "solo", "PRIMARY", UUID.randomUUID().toString()));
        return new Fixture(clusterId, live.getId(), solo.getId());
    }

    private QueueRow row(UUID node, String address, String queue, long messageCount) {
        return new QueueRow(clusterId, node, address, queue, "ANYCAST", true, messageCount, 1, 0, 0, 0, 0, 0);
    }

    @Test
    void aQueueOnBothLogicalNodesRollsUpToTwoNodeCellsAndSummedTotals() {
        Fixture f = pairPlusStandalone();
        upsert.upsertBatch(List.of(row(f.nodeA(), "ORDERS", "ORDERS", 10), row(f.nodeB(), "ORDERS", "ORDERS", 5)));

        List<QueueView> rows = aggregator
                .queues(clusterId, ResourceQuery.of(null, 1, 50, null))
                .data();

        assertThat(rows).hasSize(1);
        QueueView orders = rows.get(0);
        assertThat(orders.perNode()).hasSize(2);
        assertThat(orders.totalMessageCount()).isEqualTo(15);
        assertThat(orders.totalConsumerCount()).isEqualTo(2);
        assertThat(orders.nodesPresent()).isEqualTo(2);
        assertThat(orders.nodesTotal()).isEqualTo(2); // the pair is one logical node + the standalone
    }

    @Test
    void aQueueOnOneNodeOnlyReportsPresenceAsOneOfTwo() {
        Fixture f = pairPlusStandalone();
        upsert.upsertBatch(List.of(row(f.nodeA(), "ORDERS", "ORDERS", 3)));

        QueueView orders = aggregator
                .queues(clusterId, ResourceQuery.of(null, 1, 50, null))
                .data()
                .get(0);

        assertThat(orders.nodesPresent()).isEqualTo(1);
        assertThat(orders.nodesTotal()).isEqualTo(2);
        assertThat(orders.perNode()).hasSize(1);
    }

    @Test
    void aStaleNodesLastNumbersAreFlaggedNotDropped() {
        Fixture f = pairPlusStandalone();
        upsert.upsertBatch(List.of(row(f.nodeA(), "ORDERS", "ORDERS", 7)));
        jdbc.update(
                "UPDATE queue_snapshot SET ts = now() - interval '30 minutes' WHERE node_id = :n",
                Map.of("n", f.nodeA()));

        QueueView orders = aggregator
                .queues(clusterId, ResourceQuery.of(null, 1, 50, null))
                .data()
                .get(0);

        assertThat(orders.perNode()).hasSize(1);
        assertThat(orders.perNode().get(0).stale()).isTrue();
        assertThat(orders.perNode().get(0).messageCount()).isEqualTo(7);
    }

    @Test
    void filterAndSortApplyToTheAggregatedRows() {
        Fixture f = pairPlusStandalone();
        upsert.upsertBatch(List.of(
                row(f.nodeA(), "A", "alpha", 1), row(f.nodeA(), "B", "beta", 99), row(f.nodeA(), "C", "gamma", 50)));

        List<QueueView> byDepthDesc = aggregator
                .queues(clusterId, ResourceQuery.of(null, 1, 50, "-depth"))
                .data();
        assertThat(byDepthDesc).extracting(QueueView::queueName).containsExactly("beta", "gamma", "alpha");

        List<QueueView> filtered = aggregator
                .queues(clusterId, ResourceQuery.of("bet", 1, 50, null))
                .data();
        assertThat(filtered).extracting(QueueView::queueName).containsExactly("beta");
    }
}
