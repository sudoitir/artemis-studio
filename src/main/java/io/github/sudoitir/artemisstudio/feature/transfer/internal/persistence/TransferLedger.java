package io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code transfer_copied}: per run, the source ids that may be on the target already. One statement
 * per batch, whatever its size.
 *
 * <ul>
 *   <li>A copy records the ids it has delivered (transfer design D2), so a resumed copy skips them.
 *   <li>A move records a batch's staging ids before the target commit and removes them once staging
 *       has let them go (design D1). What is left is a batch whose commit Studio did not see
 *       finish, so a return to source must not put those messages back unasked. It is at most a
 *       batch or two.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TransferLedger {

    private final JdbcClient jdbc;

    public void record(UUID runId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        jdbc.sql("INSERT INTO transfer_copied (message_id, run_id) SELECT unnest(?::bigint[]), ?"
                        + " ON CONFLICT DO NOTHING")
                .params(messageIds.toArray(Long[]::new), runId)
                .update();
    }

    /** The ids among {@code messageIds} already copied by this run. */
    public Set<Long> known(UUID runId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT message_id FROM transfer_copied WHERE run_id = ?"
                        + " AND message_id = ANY(?::bigint[])")
                .params(runId, messageIds.toArray(Long[]::new))
                .query(Long.class)
                .list());
    }

    /** Every id recorded for this run. For a move's in-doubt ids, which are at most a batch or two. */
    public Set<Long> all(UUID runId) {
        return new HashSet<>(jdbc.sql("SELECT message_id FROM transfer_copied WHERE run_id = ?")
                .param(runId)
                .query(Long.class)
                .list());
    }

    public void remove(UUID runId, Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM transfer_copied WHERE run_id = ? AND message_id = ANY(?::bigint[])")
                .params(runId, messageIds.toArray(Long[]::new))
                .update();
    }

    public long count(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM transfer_copied WHERE run_id = ?")
                .param(runId)
                .query(Long.class)
                .single();
    }

    public void forget(UUID runId) {
        jdbc.sql("DELETE FROM transfer_copied WHERE run_id = ?").param(runId).update();
    }
}
