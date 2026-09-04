package io.github.sudoitir.artemisstudio.domain.rr;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One normalised fact about request-reply traffic, from either channel: the
 * sampler's browse (correlation identity) or the notification stream
 * (lifecycle). {@link RrCorrelator} does not know or care which channel a fact
 * came from (design.md D2).
 */
public sealed interface Observation {

    UUID clusterId();

    UUID nodeId();

    Instant at();

    /** A request message observed on a traced request address. */
    record RequestSeen(
            UUID clusterId,
            UUID nodeId,
            Instant at,
            String requestAddress,
            String messageId,
            String correlationId,
            String replyTo,
            long expiration,
            String bodyPreview,
            Map<String, Object> properties)
            implements Observation {}

    /** A reply message observed on a traced reply address or a temp reply queue. */
    record ReplySeen(
            UUID clusterId,
            UUID nodeId,
            Instant at,
            String replyDestination,
            String messageId,
            String correlationId,
            String bodyPreview,
            Map<String, Object> properties)
            implements Observation {}

    /** A consumer attached to a traced request address. */
    record ResponderUp(UUID clusterId, UUID nodeId, Instant at, String requestAddress, String consumerName)
            implements Observation {}

    /** A consumer on a traced request address closed; {@code remainingConsumers} is the count left. */
    record ResponderDown(
            UUID clusterId, UUID nodeId, Instant at, String requestAddress, String consumerName, int remainingConsumers)
            implements Observation {}

    /** A temporary reply queue's binding was removed (deleted, or its owning connection closed). */
    record TempQueueUnbound(UUID clusterId, UUID nodeId, Instant at, String queueName) implements Observation {}

    /** A message on a traced address expired without being delivered. */
    record MessageExpired(UUID clusterId, UUID nodeId, Instant at, String address, String messageId)
            implements Observation {}
}
