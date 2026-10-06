package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Decides, for a topic whose events are read under a rule of their own, whether a principal may see one.
 * Implemented by the module that owns the topic, when who may read its events is more than the permission
 * the topic declares and the resources a frame names. Such a topic is decided by its gate alone.
 */
public interface StreamGate {

    /** The topic this gate decides. */
    String topic();

    /** Whether {@code principal} may see the event whose payload is {@code data}. */
    boolean mayRead(StudioPrincipal principal, UUID clusterId, JsonNode data);
}
