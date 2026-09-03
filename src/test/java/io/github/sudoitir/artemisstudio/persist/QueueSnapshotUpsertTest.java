package io.github.sudoitir.artemisstudio.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.QueueRow;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link QueueSnapshotUpsert} against a real Postgres: the {@code ON CONFLICT}
 * upsert path and the per-node stale reap. Not {@code @Transactional} — the reap
 * needs {@code now()} to advance between batches, which only happens across
 * commits.
 */
class QueueSnapshotUpsertTest extends PostgresIntegrationTest {

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    QueueSnapshotRepository snapshots;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    private UUID clusterId;
    private UUID nodeId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId); // cascades to broker_node + queue_snapshot
        }
    }

    private void givenClusterAndNode() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeId = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n-" + UUID.randomUUID(), "PRIMARY", null))
                .getId();
    }

    private QueueRow row(String queue, long messageCount) {
        return new QueueRow(clusterId, nodeId, "addr." + queue, queue, "ANYCAST", true, messageCount, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void insertThenUpdateTheSamePkKeepsOneRowWithTheNewValues() {
        givenClusterAndNode();

        upsert.upsertBatch(List.of(row("Q1", 10), row("Q2", 5)));
        assertThat(snapshots.findByNodeId(nodeId)).hasSize(2);

        upsert.upsertBatch(List.of(row("Q1", 99)));

        QueueSnapshotEntity q1 =
                snapshots.findById(new QueueSnapshotEntity.Key(nodeId, "Q1")).orElseThrow();
        assertThat(q1.getMessageCount()).isEqualTo(99);
        assertThat(snapshots.findByNodeId(nodeId)).hasSize(2);
    }

    @Test
    void reapStaleDeletesOnlyRowsOlderThanTheSweepStartForThatNode() throws Exception {
        givenClusterAndNode();

        upsert.upsertBatch(List.of(row("OLD", 1)));
        Thread.sleep(15);
        Instant sweepStart = Instant.now();
        Thread.sleep(15);
        upsert.upsertBatch(List.of(row("FRESH", 2)));

        int reaped = upsert.reapStale(nodeId, sweepStart);

        assertThat(reaped).isEqualTo(1);
        assertThat(snapshots.findByNodeId(nodeId))
                .extracting(QueueSnapshotEntity::getQueueName)
                .containsExactly("FRESH");
    }
}
