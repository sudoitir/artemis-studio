package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Duration;
import java.util.List;

/**
 * One store that grows with use, and the bounds of its retention policy (ADR-0132).
 *
 * @param id unique among the contributor's stores; a plugin's is shown as {@code <pluginId>.<id>}
 * @param label what the Data page calls it
 * @param tables the tables it keeps bounded, schema-qualified when outside Studio's own schema
 * @param quotaUnit what its quota counts
 * @param defaultRetention the packaged retention; {@code null} keeps everything by default
 * @param minRetention the shortest retention an operator may set
 * @param maxRetention the longest retention an operator may set; {@code null} allows keeping
 *     everything ({@code forever})
 */
@PluginApi
public record StoreDef(
        String id,
        String label,
        List<String> tables,
        QuotaUnit quotaUnit,
        Duration defaultRetention,
        Duration minRetention,
        Duration maxRetention) {

    public StoreDef {
        tables = List.copyOf(tables);
    }

    /** What a store's quota counts: its size in MiB, or its rows in thousands. */
    public enum QuotaUnit {
        BYTES,
        ROWS
    }
}
