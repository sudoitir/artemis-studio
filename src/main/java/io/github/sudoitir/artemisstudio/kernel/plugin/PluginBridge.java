package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * A host feature that has something to add or remove when a plugin activates or deactivates —
 * MCP tools, stream topics, settings contributions, scheduled jobs, the feature registry (design.md
 * "PluginBridge SPI"). Implemented in the owning module, one bean per concern; the host calls every
 * {@code PluginBridge} bean's {@link #attach(PluginHandle)} after a plugin's context has refreshed
 * and before traffic is switched to it, and every bean's {@link #detach(PluginHandle)} before the
 * context is closed. A bridge that throws on {@code attach} fails that plugin's activation alone;
 * one that throws on {@code detach} is logged and does not stop the unload.
 */
public interface PluginBridge {

    void attach(PluginHandle handle);

    void detach(PluginHandle handle);
}
