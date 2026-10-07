package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * The provider's policy that decided, recorded on the held operation and its audit rows.
 *
 * @param id the provider's stable policy id
 * @param version the policy version the decision was made under
 * @param name the policy's name, for people
 */
@PluginApi
public record PolicyRef(String id, String version, String name) {

    public PolicyRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
    }
}
