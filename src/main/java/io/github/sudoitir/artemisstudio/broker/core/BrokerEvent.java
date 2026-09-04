package io.github.sudoitir.artemisstudio.broker.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One {@code activemq.notifications} notification, normalised (ADR-0026). The
 * promoted fields are the ones every screen and Phase 5's correlator need to
 * filter on; {@link #props} keeps the full {@code _AMQ_*} map verbatim so nothing
 * is lost. {@code type} is a {@code CoreNotificationType} name, or
 * {@code "UNKNOWN:<raw>"} when the broker sent a type this client's enum does not
 * carry.
 */
public record BrokerEvent(
        UUID clusterId,
        UUID nodeId,
        String type,
        Instant occurredAt,
        String address,
        String routingName,
        String consumerName,
        String sessionName,
        String connectionName,
        String remoteAddress,
        String username,
        Map<String, Object> props) {}
