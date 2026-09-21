package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run still recorded as executing when Studio starts was cut off by the stop (transfer design D6).
 * It is marked interrupted and offered for resume: a move's held messages are safe in its durable
 * staging queue, and a copy's ledger says what it has copied. The large-message spool is swept by
 * {@code CoreRelay}; a staging queue whose run is gone shows as orphaned in the transfers list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TransferRecovery {

    static final String INTERRUPTED =
            "Studio stopped while this run was executing. Its messages are intact: resume it to continue.";

    private final TransferRunRepository runs;
    private final AuditService audit;

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        for (TransferRunEntity run : runs.findByStateIn(TransferState.ACTIVE)) {
            run.finish(TransferState.INTERRUPTED, INTERRUPTED, null, Instant.now());
            runs.save(run);
            for (Long id : new Long[] {run.getAuditEventId(), run.getTargetAuditEventId()}) {
                if (id != null) {
                    audit.byId(id).ifPresent(event -> audit.fail(event, INTERRUPTED));
                }
            }
            log.warn("Transfer {} was executing when Studio stopped; marked interrupted", run.getId());
        }
    }
}
