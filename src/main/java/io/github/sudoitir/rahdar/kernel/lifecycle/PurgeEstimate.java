package io.github.sudoitir.rahdar.kernel.lifecycle;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** What a purge at a cutoff would remove: rows, and about how many bytes they take. */
@PluginApi
public record PurgeEstimate(long rows, long bytes) {}
