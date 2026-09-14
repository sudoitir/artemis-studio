package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.feature.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.platform.broker.CorePool;
import io.github.sudoitir.artemisstudio.platform.broker.CorePool.PooledSession;
import io.github.sudoitir.artemisstudio.platform.broker.CoreUrl;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BrowsedMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drains one capture queue on one node (ADR-0062 D1), stores what it drains in the
 * message index, and offers it to {@link CaptureBus} listeners.
 *
 * <p>Consuming is destructive, which is correct: Studio owns the capture queue, and
 * nothing else is entitled to what is on it. The original message is untouched — the
 * divert is non-exclusive and made a copy.
 *
 * <p>Store first, acknowledge second (ADR-0077). {@code CLIENT_ACKNOWLEDGE}, with one
 * acknowledge per batch after that drain's own rows have committed: a JMS acknowledge
 * covers every message delivered on the session so far, so it must never run ahead of
 * the store. When the store fails, nothing is acknowledged; the drain waits with
 * backoff and recovers the session, and the bounded capture queue holds the backlog
 * meanwhile. A crash mid-batch redelivers, which the writer's redelivery guard absorbs.
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

    /** Consecutive failures to read one message before it is counted as lost rather than stall the tap. */
    private static final int POISON_ATTEMPTS = 3;

    private static final Duration RETRY_INITIAL = Duration.ofSeconds(1);
    private static final Duration RETRY_MAX = Duration.ofMinutes(5);

    private final CorePool corePool;
    private final BrokerConnections connections;
    private final CaptureBus bus;
    private final MessageIndexWriter writer;

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

    /**
     * Why a node's capture recorded less than it drained since this was last taken: the
     * cause half of the loss figure, which {@link CaptureLoss} measures from counters.
     *
     * @param storeFailure the last store error, or null when every store since succeeded
     */
    public record Shortfall(long rateLimited, long unreadable, String storeFailure) {
        static final Shortfall NONE = new Shortfall(0, 0, null);

        Shortfall plus(Shortfall other) {
            return new Shortfall(
                    rateLimited + other.rateLimited,
                    unreadable + other.unreadable,
                    other.storeFailure != null ? other.storeFailure : storeFailure);
        }
    }

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

    /** The causes recorded by this subscription's drains on this node since the last call, reset by taking them. */
    public Shortfall takeShortfall(UUID nodeId, UUID subscriptionId) {
        Shortfall total = Shortfall.NONE;
        for (Drain drain : running.values()) {
            if (drain.spec.nodeId().equals(nodeId)
                    && drain.spec.subscriptionId().equals(subscriptionId)) {
                total = total.plus(drain.takeShortfall());
            }
        }
        return total;
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
        PooledSession jms = corePool.borrowForCapture(
                spec.clusterId(), CoreUrl.dialable(spec.coreUrl()), connections.coreSettingsFor(spec.clusterId()));
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

    /**
     * Stop every drain of one subscription, on every node — before its rows are deleted or its
     * capture is turned off, so nothing is written after the operator's decision.
     */
    public void stopSubscription(UUID subscriptionId) {
        running.entrySet().removeIf(entry -> {
            if (!entry.getValue().spec.subscriptionId().equals(subscriptionId)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    /** Close everything this holds open. Called at its shutdown phase. */
    public void closeAll() {
        running.values().forEach(Drain::close);
        running.clear();
    }

    /** Exponential, capped, with jitter — so many drains failing together do not retry together. */
    static long backoffMillis(int failures) {
        long base = Math.min(RETRY_MAX.toMillis(), RETRY_INITIAL.toMillis() * (1L << Math.min(failures - 1, 20)));
        return base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    /**
     * One queue's listener. Not shared: each holds its own session, batch and counters.
     *
     * <p>Its methods are synchronized because {@link #close} runs on another thread than
     * delivery. A drain waiting out a backoff waits on its own monitor, so it releases the
     * lock and {@code close} can always get in.
     */
    private final class Drain implements jakarta.jms.MessageListener {

        private final Spec spec;
        private final PooledSession jms;
        private final MessageConsumer consumer;

        /** Rows received since the last acknowledge, and the message an acknowledge would settle them with. */
        private final List<MessageIndexWriter.Captured> batch = new ArrayList<>(ACK_BATCH);

        private int delivered;
        private Message lastDelivered;

        private String failingMessageId;
        private int readFailures;
        private int storeFailures;

        private long rateLimited;
        private long unreadable;
        private String storeFailure;

        private boolean closed;

        private Drain(Spec spec, PooledSession jms, MessageConsumer consumer) {
            this.spec = spec;
            this.jms = jms;
            this.consumer = consumer;
        }

        @Override
        public synchronized void onMessage(Message message) {
            if (closed) {
                // Not recorded and not acknowledged: the broker redelivers it to the next drain.
                return;
            }
            MessageIndexWriter.Captured captured;
            try {
                captured = read(message);
            } catch (JMSException | RuntimeException e) {
                unreadable(message, e);
                return;
            }
            failingMessageId = null;
            readFailures = 0;
            lastDelivered = message;
            delivered++;
            boolean admitted = bus.publish(
                    new CaptureBus.Captured(
                            spec.clusterId(),
                            spec.subscriptionId(),
                            captured.row(),
                            captured.origAddress(),
                            captured.sourceMessageId(),
                            captured.at()),
                    spec.maxRate());
            if (admitted) {
                batch.add(captured);
            } else {
                rateLimited++;
            }
            if (delivered >= ACK_BATCH) {
                commit();
            }
        }

        private MessageIndexWriter.Captured read(Message message) throws JMSException {
            BrowsedMessage browsed = CoreMessageTransport.toBrowsed(message);
            return new MessageIndexWriter.Captured(
                    spec.clusterId(),
                    toRow(browsed),
                    stringProperty(message, ORIG_ADDRESS),
                    longProperty(message, ORIG_MESSAGE_ID),
                    Instant.now());
        }

        /** Store, then acknowledge. Returns false when the store failed and the session was recovered. */
        private boolean commit() {
            try {
                writer.capturedBatch(batch);
            } catch (RuntimeException e) {
                storeFailed(e);
                return false;
            }
            try {
                if (lastDelivered != null) {
                    lastDelivered.acknowledge();
                }
            } catch (JMSException e) {
                // Stored but not acknowledged: the broker redelivers, and the writer's
                // redelivery guard stops the batch being stored twice.
                log.debug("Capture drain of {} could not acknowledge: {}", spec.captureQueue(), e.getMessage());
            }
            batch.clear();
            delivered = 0;
            lastDelivered = null;
            storeFailures = 0;
            return true;
        }

        private void storeFailed(RuntimeException e) {
            storeFailures++;
            storeFailure =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            long delay = backoffMillis(storeFailures);
            log.warn(
                    "Capture of {} on {} could not store {} message(s): {}. Nothing was acknowledged; retrying in {} ms,"
                            + " and the capture queue holds the backlog up to its bound.",
                    spec.sourceAddress(),
                    spec.nodeName(),
                    batch.size(),
                    storeFailure,
                    delay);
            waitUnlessClosed(delay);
            recover();
        }

        /**
         * A message that cannot be read is retried like a failed store. One that keeps
         * failing is counted as lost and acknowledged — after everything delivered before
         * it has been stored — so a single poison message cannot stall the tap forever.
         */
        private void unreadable(Message message, Exception e) {
            String id = messageId(message);
            if (id != null && id.equals(failingMessageId)) {
                readFailures++;
            } else {
                failingMessageId = id;
                readFailures = 1;
            }
            if (readFailures < POISON_ATTEMPTS) {
                log.debug("Capture drain of {} could not read message {}: {}", spec.captureQueue(), id, e.getMessage());
                waitUnlessClosed(backoffMillis(readFailures));
                recover();
                return;
            }
            log.warn(
                    "Capture of {} on {} could not read message {} after {} attempts; counting it as lost: {}",
                    spec.sourceAddress(),
                    spec.nodeName(),
                    id,
                    readFailures,
                    e.getMessage());
            lastDelivered = message;
            delivered++;
            if (commit()) {
                unreadable++;
                failingMessageId = null;
                readFailures = 0;
            }
        }

        /** Hand everything unacknowledged back to the broker, and forget the rows built from it. */
        private void recover() {
            batch.clear();
            delivered = 0;
            lastDelivered = null;
            if (closed) {
                return;
            }
            try {
                jms.session().recover();
            } catch (JMSException e) {
                log.debug("Capture drain of {} could not recover its session: {}", spec.captureQueue(), e.getMessage());
            }
        }

        private void waitUnlessClosed(long millis) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            try {
                long remaining;
                while (!closed && (remaining = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                    wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized Shortfall takeShortfall() {
            Shortfall taken = new Shortfall(rateLimited, unreadable, storeFailure);
            rateLimited = 0;
            unreadable = 0;
            storeFailure = null;
            return taken;
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
         * Close the consumer first — it waits for a delivery in progress, which a waiting
         * drain abandons as soon as it is woken — then store what was received, and
         * acknowledge only if that store worked. Unacknowledged messages are redelivered to
         * the next drain rather than lost.
         */
        void close() {
            synchronized (this) {
                closed = true;
                notifyAll();
            }
            try {
                consumer.close();
            } catch (JMSException ignored) {
                // teardown
            }
            synchronized (this) {
                if (lastDelivered != null) {
                    try {
                        writer.capturedBatch(batch);
                        lastDelivered.acknowledge();
                    } catch (RuntimeException | JMSException e) {
                        log.warn(
                                "Capture drain of {} stopped with {} message(s) not stored; left unacknowledged for"
                                        + " redelivery: {}",
                                spec.captureQueue(),
                                batch.size(),
                                e.getMessage());
                    }
                    batch.clear();
                    lastDelivered = null;
                }
            }
            jms.close();
        }
    }

    private static String messageId(Message message) {
        try {
            return message.getJMSMessageID();
        } catch (JMSException | RuntimeException e) {
            return null;
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
        } catch (JMSException | RuntimeException e) {
            return null;
        }
    }
}
