package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run still recorded as executing by a replica that is gone was cut off by its stop or crash
 * (ADR-0093 D6, ADR-0152). It is marked, never resumed: its preview is stale and its operator is not
 * watching. A run another live replica is executing is left alone. Runs once at startup and then every
 * 30 seconds, on one replica.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BulkRecovery {

    static final String IN_FLIGHT =
            "Studio stopped while this queue was being acted on, so what the broker did is not known; check the broker.";

    private final BulkRunRepository runs;
    private final BulkRunItemRepository items;
    private final AuditService audit;
    private final ReplicaRegistry replicas;

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        // Read the runs first: a replica that registers after this read is not one of their owners.
        List<BulkRunEntity> executing = runs.findByStatus(BulkRunStatus.RUNNING);
        Set<UUID> alive = replicas.aliveIds();
        for (BulkRunEntity run : executing) {
            if (alive.contains(run.getReplicaId())
                    || runs.transition(run.getId(), BulkRunStatus.RUNNING, BulkRunStatus.INTERRUPTED) == 0) {
                continue;
            }
            Instant now = Instant.now();
            List<BulkRunItemEntity> rows = items.findByRunIdOrderByOrdinal(run.getId());
            for (BulkRunItemEntity item : rows) {
                if (item.getStatus() == BulkItemStatus.RUNNING) {
                    item.finish(BulkItemStatus.UNKNOWN, IN_FLIGHT, null, null, now);
                } else if (item.getStatus() == BulkItemStatus.PENDING) {
                    item.finish(BulkItemStatus.CANCELLED, null, null, null, now);
                }
            }
            items.saveAll(rows);
            run.count(
                    (int) rows.stream().filter(i -> i.getStatus().succeeded()).count(),
                    (int) rows.stream().filter(i -> i.getStatus().failed()).count(),
                    (int) rows.stream().filter(i -> i.getStatus().skipped()).count());
            run.finish(
                    BulkRunStatus.INTERRUPTED, "Studio stopped while this run was executing. It was not resumed.", now);
            runs.save(run);
            if (run.getAuditEventId() != null) {
                audit.byId(run.getAuditEventId()).ifPresent(event -> audit.fail(event, run.getError()));
            }
            log.warn("Bulk run {} was executing when Studio stopped; marked interrupted", run.getId());
        }
    }
}
