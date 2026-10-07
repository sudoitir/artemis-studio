package io.github.sudoitir.artemisstudio.feature.messages;

import io.github.sudoitir.artemisstudio.feature.messages.web.MessageRequests.MessageActionRequest;
import java.util.UUID;

/**
 * What the gate holds of a message action. One record per action, because a gated operation is found by its
 * parameter class (ADR-0179); {@code node} is the node the caller asked for, or {@code null} for the one Studio picks.
 */
sealed interface MessageActionParams {

    UUID clusterId();

    String queue();

    UUID nodeId();

    MessageActionRequest request();

    boolean override();

    record MessageMoveParams(UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageRetryParams(UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageDeleteParams(
            UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageExpireParams(
            UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    /** A purge removes every message, so it carries no selection. */
    record QueuePurgeParams(UUID clusterId, String queue, UUID nodeId, boolean override) {}
}
