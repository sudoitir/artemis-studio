package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.Disposition;
import io.github.sudoitir.artemisstudio.feature.plugins.messaging.PluginMessage;
import io.github.sudoitir.artemisstudio.feature.plugins.messaging.PluginMessageHandler;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Which plugins can receive messages now, and the one door deliveries go through (ADR-0111).
 *
 * <p>On attach, a plugin's own {@link PluginMessageHandler} bean is recorded; on detach it is
 * forgotten and the plugin's drains are stopped at once, so nothing is delivered to a plugin whose
 * context is closing, and what they held unacknowledged returns to the broker. An Instant update
 * attaches the new version before the old one detaches; ownership is by handle, as in the other
 * bridges, so the old version's detach does not stop the new version's deliveries.
 */
@Slf4j
@Component
public class PluginHandlers implements PluginBridge {

    private record Registered(PluginHandle handle, PluginMessageHandler handler) {}

    private final Map<String, Registered> byPlugin = new ConcurrentHashMap<>();
    private final PluginDrains drains;

    PluginHandlers(@Lazy PluginDrains drains) {
        this.drains = drains;
    }

    @Override
    public void attach(PluginHandle handle) {
        Map<String, PluginMessageHandler> handlers = handle.beansOfType(PluginMessageHandler.class);
        if (handlers.isEmpty()) {
            byPlugin.remove(handle.id());
            return;
        }
        if (handlers.size() > 1) {
            throw new IllegalStateException("The plugin declares " + handlers.size() + " PluginMessageHandler beans "
                    + handlers.keySet() + "; it may declare one.");
        }
        byPlugin.put(
                handle.id(), new Registered(handle, handlers.values().iterator().next()));
    }

    @Override
    public void detach(PluginHandle handle) {
        Registered current = byPlugin.get(handle.id());
        if (current == null || current.handle() != handle || !byPlugin.remove(handle.id(), current)) {
            return;
        }
        drains.stopPlugin(handle.id());
    }

    /** Plugins that are running and can take deliveries. */
    public Set<String> receiving() {
        return Set.copyOf(byPlugin.keySet());
    }

    public boolean isReceiving(String pluginId) {
        return byPlugin.containsKey(pluginId);
    }

    /**
     * Hand a message to its plugin, on the calling (Studio) thread, with the plugin's classloader
     * set and the call counted as in flight. Empty when the plugin is not receiving now.
     *
     * @throws Exception whatever the handler threw
     */
    Optional<Disposition> deliver(String pluginId, PluginMessage message) throws Exception {
        Registered registered = byPlugin.get(pluginId);
        if (registered == null) {
            return Optional.empty();
        }
        try {
            Disposition d =
                    registered.handle().runInPlugin(() -> registered.handler().onMessage(message));
            return Optional.of(d == null ? Disposition.REJECT : d);
        } finally {
            // Core delivery threads are shared and long-lived: never leave one pointing at a plugin's
            // classloader, whatever it was before (see PluginJobBridge).
            Thread.currentThread().setContextClassLoader(PluginHandlers.class.getClassLoader());
        }
    }
}
