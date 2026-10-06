package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;

/** A queue or an address of a cluster, by name: what a resource permission is checked against. */
@PluginApi
public record ResourceRef(ResourceKind kind, String name) {

    public static ResourceRef queue(String name) {
        return new ResourceRef(ResourceKind.QUEUE, name);
    }

    public static ResourceRef address(String name) {
        return new ResourceRef(ResourceKind.ADDRESS, name);
    }
}
