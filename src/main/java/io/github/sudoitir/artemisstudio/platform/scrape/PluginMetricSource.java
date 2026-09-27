package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Map;
import java.util.UUID;

/**
 * One metric a plugin publishes (ADR-0113). Define it as a bean in the plugin's own context, and
 * declare the metric under {@code metrics} in {@code plugin.json}. On every tier-B scrape of a
 * cluster Studio calls {@link #sample(UUID)} and stores the values in its time series, exports
 * them to Prometheus as {@code studio_plugin_metric}, and evaluates threshold alert rules on them.
 *
 * <p>{@link #sample(UUID)} must answer from state the plugin already holds: a call that takes
 * longer than two seconds is abandoned for that scrape.
 */
@PluginApi
public interface PluginMetricSource {

    /** The metric's identifier, {@code <plugin id>:<name>}, as declared in {@code plugin.json}. */
    String metric();

    /**
     * The current value for each subject on this cluster, for example per job. A subject missing
     * from the map has no sample this tick; a non-finite value is dropped.
     */
    Map<String, Double> sample(UUID clusterId);
}
