package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * A bean that puts stores under the data lifecycle (ADR-0134). Core modules and plugins implement
 * it the same way; a plugin's stores join the lifecycle when it activates and leave when it stops.
 */
@PluginApi
public interface HousekeepingContributor {

    List<ManagedStore> stores();
}
