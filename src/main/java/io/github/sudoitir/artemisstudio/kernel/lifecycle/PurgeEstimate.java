package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** What a purge at a cutoff would remove: rows, and about how many bytes they take. */
@PluginApi
public record PurgeEstimate(long rows, long bytes) {}
