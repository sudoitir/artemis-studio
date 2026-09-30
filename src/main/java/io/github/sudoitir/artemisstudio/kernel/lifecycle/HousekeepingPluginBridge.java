package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Puts a plugin's {@link HousekeepingContributor} stores under the lifecycle while it is active (ADR-0132). */
@Component
@RequiredArgsConstructor
class HousekeepingPluginBridge implements PluginBridge {

    private final LifecycleRegistry registry;

    /** Which handle owns each plugin id's stores, so a superseded version's detach leaves the new one's in place. */
    private final Map<String, PluginHandle> owners = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        List<HousekeepingContributor> contributors =
                List.copyOf(handle.beansOfType(HousekeepingContributor.class).values());
        owners.put(handle.id(), handle);
        registry.addPlugin(handle, contributors);
    }

    @Override
    public void detach(PluginHandle handle) {
        if (owners.remove(handle.id(), handle)) {
            registry.removePlugin(handle.id());
        }
    }
}
