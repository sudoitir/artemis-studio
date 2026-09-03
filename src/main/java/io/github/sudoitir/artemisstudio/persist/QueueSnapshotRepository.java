package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to {@code queue_snapshot} for the cross-node aggregator. Writes are
 * {@link QueueSnapshotUpsert}'s job, not this repository's — never call
 * {@code save}/{@code delete} here (ADR-0016).
 */
public interface QueueSnapshotRepository extends JpaRepository<QueueSnapshotEntity, QueueSnapshotEntity.Key> {

    List<QueueSnapshotEntity> findByClusterId(UUID clusterId);

    List<QueueSnapshotEntity> findByNodeId(UUID nodeId);
}
