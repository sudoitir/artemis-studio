package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/** What a store holds now: its (estimated) rows and its size on disk, indexes and TOAST included. */
@PluginApi
public record StoreUsage(long rows, long bytes) {}
