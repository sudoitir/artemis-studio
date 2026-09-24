/**
 * {@link io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor} is
 * exposed to other modules only through {@link io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle#descriptor()}
 * — a bridge reads a running plugin's own descriptor from its handle, never by reaching into the
 * parser or the store.
 */
@org.springframework.modulith.NamedInterface("descriptor")
package io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor;
