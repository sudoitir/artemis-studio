package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.EventCursor;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldEvent;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldFilter;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link HeldOperations} as one plugin sees them: only the requests it held as the provider. A plugin that never
 * provided approvals sees none. Bound to the plugin's id by the host, so no call can name another provider.
 */
final class ScopedHeldOperations implements HeldOperations {

    private final HeldOperationsService service;
    private final String pluginId;

    ScopedHeldOperations(HeldOperationsService service, String pluginId) {
        this.service = service;
        this.pluginId = pluginId;
    }

    @Override
    public Optional<HeldOperationView> get(UUID id) {
        return service.get(pluginId, id);
    }

    @Override
    public List<HeldOperationView> list(HeldFilter filter) {
        return service.list(pluginId, filter);
    }

    @Override
    public List<HeldEvent> eventsAfter(EventCursor cursor, int limit) {
        return service.eventsAfter(pluginId, cursor, limit);
    }

    @Override
    public boolean expire(UUID id, String reason) {
        return service.expire(pluginId, id, reason);
    }
}
