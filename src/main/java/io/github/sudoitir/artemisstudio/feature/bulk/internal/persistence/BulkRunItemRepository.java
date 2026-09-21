package io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code bulk_run_item} access (ADR-0011). */
public interface BulkRunItemRepository extends JpaRepository<BulkRunItemEntity, UUID> {

    List<BulkRunItemEntity> findByRunIdOrderByOrdinal(UUID runId);
}
