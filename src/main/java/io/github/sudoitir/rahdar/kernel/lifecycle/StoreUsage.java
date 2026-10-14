package io.github.sudoitir.rahdar.kernel.lifecycle;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** What a store holds now: its (estimated) rows and its size on disk, indexes and TOAST included. */
@PluginApi
public record StoreUsage(long rows, long bytes) {}
