package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import java.util.List;

/**
 * {@link PluginHost#purgePlan(String)}: what {@link PluginHost#purge(String, String)} would
 * remove, estimated without touching anything (design.md §4 — {@code pg_class.reltuples} and
 * {@code pg_total_relation_size} rather than {@code count(*)}, so the dry run stays cheap).
 */
public record PurgePlan(
        String schema, List<TableEstimate> tables, long grantsCount, long settingsCount, long artifactsCount) {

    public PurgePlan {
        tables = List.copyOf(tables);
    }

    public record TableEstimate(String name, long estimatedRows, long bytes) {}
}
