package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The {@link GatedOperationCatalogue} over the {@link GatedOperationRegistry}. A class of its own, because the
 * registry is the host's and must not be handed to plugins.
 */
@Component
@PluginApi
class GatedOperations implements GatedOperationCatalogue {

    private final GatedOperationRegistry registry;

    GatedOperations(GatedOperationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<GatedOperationInfo> list() {
        return registry.all().stream()
                .map(operation -> new GatedOperationInfo(operation.type(), operation.version(), operation.mode()))
                .sorted(Comparator.comparing(GatedOperationInfo::type))
                .toList();
    }
}
