package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * One batch for an {@link AuditReceiver}: records in commit order, and the {@code gap} of records
 * lost to retention since the previous acknowledged batch, or null when there is none. A batch may
 * hold only a gap.
 */
@PluginApi
public record AuditDelivery(List<AuditRecord> records, AuditGap gap) {

    public AuditDelivery {
        records = List.copyOf(records);
    }
}
