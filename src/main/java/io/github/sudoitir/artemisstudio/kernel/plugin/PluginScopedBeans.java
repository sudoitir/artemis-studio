package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Map;

/**
 * A host facility that gives each plugin objects bound to that plugin alone (ADR-0111).
 *
 * <p>{@code @PluginApi} beans are singletons every plugin shares, so none of them can tell which
 * plugin is calling. A facility that must be scoped (a plugin's secrets, its message
 * registrations) implements this instead: the host calls {@link #beansFor} while it builds a
 * plugin's context, and registers every returned object as a singleton there under its key,
 * before the context refreshes, so the plugin's own beans can inject it.
 *
 * <p>A returned object is a host object that knows the plugin's id and holds nothing of the
 * plugin's own — no bean, no class, no classloader — so it never keeps an unloaded plugin
 * alive. Nothing it exposes may take a plugin id: that is what stops one plugin acting as
 * another.
 */
public interface PluginScopedBeans {

    /** The objects to register in {@code pluginId}'s context, by bean name. */
    Map<String, Object> beansFor(String pluginId);
}
