package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.artemisstudio.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run still recorded as executing when Studio starts was cut off by the stop (ADR-0093 D6). It is
 * marked, never resumed: its preview is stale and its operator is not watching.
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

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        for (BulkRunEntity run : runs.findByStatus(BulkRunStatus.RUNNING)) {
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
