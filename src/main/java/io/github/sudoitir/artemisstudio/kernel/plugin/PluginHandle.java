package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.util.concurrent.Callable;
import org.springframework.context.ApplicationContext;

/**
 * What a {@link PluginBridge} is handed on {@link PluginBridge#attach(PluginHandle)} and
 * {@link PluginBridge#detach(PluginHandle)}: the running plugin's identity, its own Spring context
 * and classloader, and {@link #runInPlugin(Callable)} — the one sanctioned way for host code to
 * call into a plugin's beans (a bridge's wrapped handler, a scheduled job's task) with the thread
 * context classloader switched to the plugin's own and the call counted as in-flight, so the
 * plugin's unload drains it the same way the gateway's own calls are drained.
 */
public interface PluginHandle {

    /** The plugin's id, as declared in its {@code plugin.json}. */
    String id();

    PluginDescriptor descriptor();

    /** The plugin's own child {@code ApplicationContext}, parented on the curated API context. */
    ApplicationContext applicationContext();

    ClassLoader classLoader();

    /**
     * The plugin's own beans of a type, by name — not its parents' — for a bridge that looks for a
     * contribution without needing the context itself.
     */
    default <T> java.util.Map<String, T> beansOfType(Class<T> type) {
        return applicationContext().getBeansOfType(type);
    }

    /**
     * Runs {@code call} with the thread context classloader set to this plugin's own, restoring
     * the previous one afterward, and counts the call as in-flight for the duration so an unload
     * waits for it to finish (or times out) before closing the plugin's context.
     */
    <T> T runInPlugin(Callable<T> call) throws Exception;
}
