package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.stream.StreamGate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Who sees a broker event on the stream: the same readers as the event list ({@link BrokerEventService}).
 * An event is read through its address, or with the cluster when it has none, and one that also names a
 * queue or address in {@code routingName} is read only by someone who may read that too.
 */
@Component
@RequiredArgsConstructor
class BrokerEventStreamGate implements StreamGate {

    private final PermissionResolver permissions;

    @Override
    public String topic() {
        return EventsModule.TOPIC;
    }

    @Override
    public boolean mayRead(StudioPrincipal principal, UUID clusterId, JsonNode data) {
        String address = text(data, "address");
        boolean readsAddress = address == null
                ? permissions.can(principal, clusterId, Permissions.CLUSTER_READ)
                : permissions.can(principal, clusterId, ResourceRef.address(address), Permissions.ADDRESS_READ);
        if (!readsAddress) {
            return false;
        }
        String routingName = text(data, "routingName");
        return routingName == null
                || permissions.can(principal, clusterId, Permissions.ADDRESS_READ)
                || permissions.can(principal, clusterId, ResourceRef.queue(routingName), Permissions.QUEUE_READ)
                || permissions.can(principal, clusterId, ResourceRef.address(routingName), Permissions.ADDRESS_READ);
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isString() ? value.asString() : null;
    }
}
