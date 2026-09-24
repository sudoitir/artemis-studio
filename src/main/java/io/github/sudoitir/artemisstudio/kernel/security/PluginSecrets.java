package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A plugin's own secrets (ADR-0111): named values encrypted at rest with Studio's secret key,
 * which only this plugin can read. Inject it into a plugin bean; Studio puts one bound to the
 * plugin into its context.
 *
 * <p>Nothing Studio exposes returns a value — no HTTP, stream or assistant interface — and a
 * plugin should keep it that way: accept a secret, never echo one. {@link #list()} reports only
 * which names are set and when they last changed. Writes and deletes are audited by name; the
 * value is never logged, audited or put in an error. Purging the plugin deletes every secret it
 * holds.
 */
@PluginApi
public final class PluginSecrets {

    /** What may be said about a secret without revealing it. */
    @PluginApi
    public record SecretInfo(String name, Instant updatedAt) {}

    private final PluginSecretStore store;
    private final String pluginId;

    PluginSecrets(PluginSecretStore store, String pluginId) {
        this.store = store;
        this.pluginId = pluginId;
    }

    /**
     * Store or replace a secret.
     *
     * @param name 1 to 200 characters of letters, digits, {@code . _ -}
     * @param value at most 64 KiB once encoded as UTF-8
     */
    public void put(String name, String value) {
        store.put(pluginId, name, value);
    }

    /** The secret's value, or empty when none is stored under that name. */
    public Optional<String> get(String name) {
        return store.get(pluginId, name);
    }

    /** Whether a secret was deleted. Deleting one that does not exist is not an error. */
    public boolean delete(String name) {
        return store.delete(pluginId, name);
    }

    /** The names of this plugin's secrets and when each last changed, in name order. */
    public List<SecretInfo> list() {
        return store.list(pluginId);
    }
}
