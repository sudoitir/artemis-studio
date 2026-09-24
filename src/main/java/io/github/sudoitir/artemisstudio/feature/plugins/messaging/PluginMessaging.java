package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import java.util.Optional;

/**
 * A plugin's door to its clusters' messages (ADR-0111). Inject it into a plugin bean; Studio puts
 * one bound to the plugin into its context. Everything here acts only on this plugin's own
 * registrations.
 *
 * <p>Registrations are <em>declarative</em>: {@link #register} stores what the plugin wants, and
 * Studio makes it true on every serving node of the cluster, now and after every restart, failover
 * or broker restart, and undoes it when the registration is removed or the plugin stops running.
 * Deliveries go to the plugin's {@link PluginMessageHandler} bean. Studio owns every connection,
 * thread and broker object involved; the plugin owns none.
 */
@PluginApi
public final class PluginMessaging {

    private final PluginMessagingService service;
    private final String pluginId;

    PluginMessaging(PluginMessagingService service, String pluginId) {
        this.service = service;
        this.pluginId = pluginId;
    }

    /**
     * Store a registration, replacing any under the same key, and try to make it true at once.
     *
     * @throws RegistrationRefusedException when the acting user may not use the cluster in this mode,
     *     the cluster is unknown, or the queue is one Studio reserves for itself
     */
    public MessageRegistration register(RegistrationSpec spec) {
        return service.register(pluginId, spec);
    }

    /** Remove a registration and everything it created on the broker. Removing an unknown key is a no-op. */
    public boolean unregister(String key) {
        return service.unregister(pluginId, key);
    }

    public Optional<MessageRegistration> registration(String key) {
        return service.registration(pluginId, key);
    }

    public List<MessageRegistration> registrations() {
        return service.registrations(pluginId);
    }

    /**
     * Send a message through a serving node of the cluster.
     *
     * @throws RegistrationRefusedException when the acting user lacks {@code message:send} there
     */
    public void send(OutboundMessage message) {
        service.send(pluginId, message);
    }
}
