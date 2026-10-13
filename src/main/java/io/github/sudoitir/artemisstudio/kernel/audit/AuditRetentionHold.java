package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * Tells retention which audit records to keep. A plugin that declares {@code audit.retentionHold} in
 * its manifest provides exactly one. Retention asks before every purge and deletes nothing if the
 * answer is an exception, takes longer than the delivery timeout or holds more than 200 entries.
 */
@PluginApi
public interface AuditRetentionHold {

    List<AuditHold> holds();
}
