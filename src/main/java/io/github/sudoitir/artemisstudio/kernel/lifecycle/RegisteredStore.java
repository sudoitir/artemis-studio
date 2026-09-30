package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.function.Function;

/**
 * A store as the lifecycle knows it: its installation-wide id, who contributed it, and how to call
 * it. A plugin's store is called inside the plugin's classloader and counted as in-flight, so an
 * unload waits for a running purge.
 *
 * @param id the store's own id, prefixed {@code <pluginId>.} for a plugin's
 * @param source {@code core}, or the contributing plugin's id
 * @param plugin the contributing plugin, {@code null} for a core store
 */
public record RegisteredStore(String id, String source, ManagedStore store, PluginHandle plugin) {

    public static final String CORE = "core";

    public StoreDef def() {
        return store.def();
    }

    public <T> T call(Function<ManagedStore, T> call) {
        if (plugin == null) {
            return call.apply(store);
        }
        try {
            return plugin.runInPlugin(() -> call.apply(store));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
