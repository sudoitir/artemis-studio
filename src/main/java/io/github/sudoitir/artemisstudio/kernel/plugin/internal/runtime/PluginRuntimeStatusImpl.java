package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginRuntimeStatus;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Backed by the same {@link PluginRuntimeRegistry} the gateway itself reads. */
@Component
@RequiredArgsConstructor
class PluginRuntimeStatusImpl implements PluginRuntimeStatus {

    private final PluginRuntimeRegistry registry;

    @Override
    public Optional<Duration> updating(String pluginId) {
        return registry.get(pluginId)
                .filter(PluginRuntimeRegistry.Updating.class::isInstance)
                .map(PluginRuntimeRegistry.Updating.class::cast)
                .map(u -> Duration.ofSeconds(u.retryAfterSeconds()));
    }
}
