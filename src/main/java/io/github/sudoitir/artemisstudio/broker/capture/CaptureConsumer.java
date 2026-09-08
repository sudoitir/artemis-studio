package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.core.CorePool;
import io.github.sudoitir.artemisstudio.broker.core.CorePool.PooledSession;
import io.github.sudoitir.artemisstudio.broker.core.CoreUrl;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import jakarta.annotation.PreDestroy;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drains one capture queue on one node (ADR-0062 D1) and offers what it drains to
 * {@link CaptureBus}.
 *
 * <p>Consuming is destructive, which is correct: Studio owns the capture queue, and
 * nothing else is entitled to what is on it. The original message is untouched — the
 * divert is non-exclusive and made a copy.
 *
 * <p>{@code CLIENT_ACKNOWLEDGE} with a batch acknowledge after the sinks have run.
 * A JMS acknowledge acknowledges every message delivered on the session so far, so
 * one call per batch is both correct and cheaper than one per message; the cost of a
 * crash mid-batch is that the batch is redelivered, which the index's
 * {@code ON CONFLICT DO NOTHING} absorbs.
 *
 * <p>Identity comes from the queue being drained, not from a header (D2): this
 * consumer was started for a known source address, so every message it receives is
 * attributed to that address whatever the broker did or did not copy into
 * {@code _AMQ_ORIG_ADDRESS}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureConsumer {

    /** Artemis' own names for the properties a divert copies onto the copy. */
    private static final String ORIG_ADDRESS = "_AMQ_ORIG_ADDRESS";

    private static final String ORIG_MESSAGE_ID = "_AMQ_ORIG_MESSAGE_ID";

    /** Messages acknowledged in one go. Larger trades redelivery risk for fewer round trips. */
    private static final int ACK_BATCH = 50;

    private final CorePool corePool;
    private final BrokerConnections connections;
    private final CaptureBus bus;

    /**
     * Running drains, by node and tap name.
     *
     * <p>The node is part of the key and has to be. A tap's name identifies the
     * subscription and the source address, not the broker — the same tap exists on
     * every live node — so keying on the name alone made one node's drain look like
     * every node's, and a promoted backup was reported as already draining while the
     * only live drain was still pointed at the node that had just died.
     */
    private final Map<String, Drain> running = new ConcurrentHashMap<>();

    private static String key(UUID nodeId, String name) {
        return nodeId + "|" + name;
    }

    /** What a drain needs to know to attribute and bound what it reads. */
    public record Spec(
            UUID clusterId,
            UUID nodeId,
            String nodeName,
            String coreUrl,
            UUID subscriptionId,
            /** The tap's name — the divert's name, and this drain's key. */
            String name,
            /** The queue actually drained, which is {@link CaptureNames#queueOf} of the name. */
            String captureQueue,
            String sourceAddress,
            int bodyCapBytes,
            int maxRate) {}

    /** Whether a drain is running for this tap on this node. */
    public boolean isDraining(UUID nodeId, String name) {
        return running.containsKey(key(nodeId, name));
    }

    /** The tap names currently being drained on this node. */
    public Set<String> drainingOn(UUID nodeId) {
        String prefix = nodeId + "|";
        return running.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Start draining, or do nothing if already draining. Throws when the broker will
     * not let Studio consume — which is a per-node fact the caller records, not a
     * reason to stop reconciling the other nodes.
     */
    public void start(Spec spec) throws JMSException {
        if (running.containsKey(key(spec.nodeId(), spec.name()))) {
            return;
        }
        // A node's Core URL is often the connector name the broker's own topology
        // reported — `host:port`, with no scheme — and the Core client rejects that
        // with "Schema <host> not found". Normalising here rather than at the caller
        // keeps one answer to what a dialable Core URL is.
        PooledSession jms = corePool.borrow(
                spec.clusterId(),
                CoreUrl.dialable(spec.coreUrl()),
                connections.coreSettingsFor(spec.clusterId()),
                Session.CLIENT_ACKNOWLEDGE);
        try {
            Session session = jms.session();
            MessageConsumer consumer = session.createConsumer(session.createQueue(spec.captureQueue()));
            Drain drain = new Drain(spec, jms, consumer);
            consumer.setMessageListener(drain);
            running.put(key(spec.nodeId(), spec.name()), drain);
            log.info(
                    "Draining capture queue {} for {} on {}",
                    spec.captureQueue(),
                    spec.sourceAddress(),
                    spec.nodeName());
        } catch (JMSException e) {
            jms.close();
            throw e;
        }
    }

    /** Stop draining a tap on one node. Safe to call for one that is not running. */
    public void stop(UUID nodeId, String name) {
        Drain drain = running.remove(key(nodeId, name));
        if (drain != null) {
            drain.close();
        }
    }

    @PreDestroy
    void shutdown() {
        running.values().forEach(Drain::close);
        running.clear();
    }

    /** One queue's listener. Not shared: each holds its own session and ack counter. */
    private final class Drain implements jakarta.jms.MessageListener {

        private final Spec spec;
        private final PooledSession jms;
        private final MessageConsumer consumer;
        private int sinceAck;
        private Message lastDelivered;

        private Drain(Spec spec, PooledSession jms, MessageConsumer consumer) {
            this.spec = spec;
            this.jms = jms;
            this.consumer = consumer;
        }

        @Override
        public void onMessage(Message message) {
            try {
                lastDelivered = message;
                BrowsedMessage browsed = CoreMessageTransport.toBrowsed(message);
                bus.publish(
                        new CaptureBus.Captured(
                                spec.clusterId(),
                                spec.subscriptionId(),
                                toRow(browsed),
                                stringProperty(message, ORIG_ADDRESS),
                                longProperty(message, ORIG_MESSAGE_ID),
                                Instant.now()),
                        spec.maxRate());
                if (++sinceAck >= ACK_BATCH) {
                    acknowledge();
                }
            } catch (JMSException e) {
                // Not fatal to the drain: the message is redelivered, and a queue that
                // keeps failing shows up as a growing ring rather than as a silent stop.
                log.debug("Capture drain of {} could not read a message: {}", spec.captureQueue(), e.getMessage());
            }
        }

        /**
         * The body cap is applied here, at the edge, and not in the writer. A message
         * over {@code min-large-message-size} is already in memory by the time it is
         * a {@code BrowsedMessage}, so this bounds what reaches Postgres rather than
         * what reaches Studio — the transfer itself is bounded by the divert filter and
         * by not capturing addresses that carry large messages.
         */
        private Row toRow(BrowsedMessage message) {
            String body = message.body();
            boolean truncated = message.bodyTruncated();
            if (body != null && body.length() > spec.bodyCapBytes()) {
                body = body.substring(0, spec.bodyCapBytes());
                truncated = true;
            }
            Map<String, Object> properties = new HashMap<>();
            properties.putAll(message.stringProperties());
            properties.putAll(message.intProperties());
            properties.putAll(message.longProperties());
            properties.putAll(message.doubleProperties());
            properties.putAll(message.booleanProperties());
            return new Row(
                    spec.nodeId(),
                    spec.nodeName(),
                    spec.sourceAddress(),
                    spec.sourceAddress(),
                    message.messageId(),
                    message.type(),
                    message.durable(),
                    message.priority(),
                    message.timestamp(),
                    message.expiration(),
                    message.size(),
                    message.contentType(),
                    message.correlationId(),
                    message.groupId(),
                    message.userId(),
                    message.replyTo(),
                    body,
                    truncated,
                    Map.copyOf(properties),
                    Source.BROKER,
                    null,
                    null,
                    "CAPTURED",
                    null);
        }

        /**
         * A JMS acknowledge acknowledges everything delivered on the session so far,
         * so acknowledging the most recent message settles the whole batch.
         */
        private void acknowledge() {
            // Commit first, acknowledge second. The other order would let the broker
            // forget a message Studio has not stored.
            bus.flush();
            try {
                if (lastDelivered != null) {
                    lastDelivered.acknowledge();
                }
            } catch (JMSException e) {
                log.debug("Capture drain of {} could not acknowledge: {}", spec.captureQueue(), e.getMessage());
            }
            sinceAck = 0;
            lastDelivered = null;
        }

        void close() {
            if (sinceAck > 0) {
                // The rows are already written; without this the broker would redeliver
                // them and the writer's redelivery guard would have to absorb it.
                acknowledge();
            }
            try {
                consumer.close();
            } catch (JMSException ignored) {
                // teardown
            }
            jms.close();
        }
    }

    private static String stringProperty(Message message, String name) {
        try {
            return message.getStringProperty(name);
        } catch (JMSException e) {
            return null;
        }
    }

    private static Long longProperty(Message message, String name) {
        try {
            return message.propertyExists(name) ? message.getLongProperty(name) : null;
        } catch (JMSException e) {
            return null;
        }
    }
}
