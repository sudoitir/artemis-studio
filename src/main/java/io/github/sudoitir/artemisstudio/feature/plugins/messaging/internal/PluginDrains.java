package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.Disposition;
import io.github.sudoitir.artemisstudio.feature.plugins.messaging.PluginMessage;
import io.github.sudoitir.artemisstudio.feature.plugins.messaging.RegistrationMode;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.CorePool;
import io.github.sudoitir.artemisstudio.platform.broker.CorePool.PooledSession;
import io.github.sudoitir.artemisstudio.platform.broker.CoreUrl;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.ActiveMQQueueMaxConsumerLimitReached;
import org.springframework.stereotype.Component;

/**
 * The Core consumers that deliver registrations' messages to plugins, one per registration and
 * node, on Studio's plugin session pool (ADR-0111).
 *
 * <p>Every session is {@code CLIENT_ACKNOWLEDGE} and carries one consumer, so an acknowledge
 * settles exactly the message just handled. A tap's copy is acknowledged whatever the handler
 * said — the original never left its queue. A consumed message is acknowledged only on
 * {@link Disposition#ACCEPT}; a rejection or an exception recovers the session, and the broker
 * redelivers it within its own {@code max-delivery-attempts}. A drain that stops, or a Studio that
 * dies, acknowledges nothing it had not already settled, so what it held is redelivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginDrains {

    private final CorePool corePool;
    private final BrokerConnections connections;
    private final PluginHandlers handlers;

    /** One drain's identity and what it reads. {@code source} is the queue name the consumer opens. */
    record Spec(
            UUID registrationId,
            String pluginId,
            String key,
            RegistrationMode mode,
            UUID clusterId,
            UUID nodeId,
            String nodeName,
            String coreUrl,
            String queue,
            String source) {}

    /** Why a drain could not start: the broker let another consumer have it, or anything else. */
    static final class StartFailure extends Exception {
        final boolean servedElsewhere;

        StartFailure(String message, boolean servedElsewhere, Throwable cause) {
            super(message, cause);
            this.servedElsewhere = servedElsewhere;
        }
    }

    private final Map<String, Drain> running = new ConcurrentHashMap<>();

    private static String key(UUID registrationId, UUID nodeId) {
        return registrationId + "|" + nodeId;
    }

    boolean isRunning(UUID registrationId, UUID nodeId) {
        return running.containsKey(key(registrationId, nodeId));
    }

    /** The registrations draining on a node. */
    Set<UUID> runningOn(UUID nodeId) {
        return running.values().stream()
                .filter(d -> d.spec.nodeId().equals(nodeId))
                .map(d -> d.spec.registrationId())
                .collect(Collectors.toSet());
    }

    Set<UUID> nodesOf(UUID registrationId) {
        return running.values().stream()
                .filter(d -> d.spec.registrationId().equals(registrationId))
                .map(d -> d.spec.nodeId())
                .collect(Collectors.toSet());
    }

    /** Start draining, or do nothing if already draining. */
    void start(Spec spec) throws StartFailure {
        if (running.containsKey(key(spec.registrationId(), spec.nodeId()))) {
            return;
        }
        PooledSession jms;
        try {
            jms = corePool.borrowForPlugins(
                    spec.clusterId(), CoreUrl.dialable(spec.coreUrl()), connections.coreSettingsFor(spec.clusterId()));
        } catch (JMSException e) {
            throw new StartFailure(
                    "Studio could not open a Core session to " + spec.nodeName() + ": " + reason(e), false, e);
        }
        try {
            MessageConsumer consumer =
                    jms.session().createConsumer(jms.session().createQueue(spec.source()));
            Drain drain = new Drain(spec, jms, consumer);
            consumer.setMessageListener(drain);
            jms.connection()
                    .setExceptionListener(failure -> connectionFailed(spec.clusterId(), spec.nodeId(), failure));
            running.put(key(spec.registrationId(), spec.nodeId()), drain);
            log.info(
                    "Delivering {} of {} on {} to plugin {} ({})",
                    spec.mode() == RegistrationMode.TAP ? "a copy" : "messages",
                    spec.queue(),
                    spec.nodeName(),
                    spec.pluginId(),
                    spec.key());
        } catch (JMSException e) {
            jms.close();
            boolean elsewhere = causedByMaxConsumers(e);
            throw new StartFailure(
                    elsewhere
                            ? "Another Studio instance sharing this database is delivering this on " + spec.nodeName()
                            : "The broker refused a consumer on " + spec.source() + " at " + spec.nodeName() + ": "
                                    + reason(e),
                    elsewhere,
                    e);
        }
    }

    void stop(UUID registrationId, UUID nodeId) {
        Drain drain = running.remove(key(registrationId, nodeId));
        if (drain != null) {
            drain.close();
        }
    }

    /** Stop every drain of a registration, on every node. */
    public void stopRegistration(UUID registrationId) {
        running.entrySet().removeIf(e -> {
            if (!e.getValue().spec.registrationId().equals(registrationId)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
    }

    /** Stop every drain delivering to a plugin, before its context closes. */
    void stopPlugin(String pluginId) {
        running.entrySet().removeIf(e -> {
            if (!e.getValue().spec.pluginId().equals(pluginId)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
    }

    /** A dead connection ends every drain on its node; the next pass starts them again. */
    void connectionFailed(UUID clusterId, UUID nodeId, JMSException failure) {
        running.entrySet().removeIf(e -> {
            Spec spec = e.getValue().spec;
            if (!spec.clusterId().equals(clusterId) || !spec.nodeId().equals(nodeId)) {
                return false;
            }
            e.getValue().abandon();
            log.warn(
                    "Plugin delivery of {} on {} lost its broker connection and restarts on the next pass: {}",
                    spec.queue(),
                    spec.nodeName(),
                    failure.getMessage());
            return true;
        });
    }

    private static boolean causedByMaxConsumers(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ActiveMQQueueMaxConsumerLimitReached) {
                return true;
            }
            if (t instanceof JMSException j && j.getLinkedException() instanceof ActiveMQQueueMaxConsumerLimitReached) {
                return true;
            }
        }
        return false;
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** One registration on one node. */
    private final class Drain implements MessageListener {

        private final Spec spec;
        private final PooledSession jms;
        private final MessageConsumer consumer;
        private volatile boolean closed;

        Drain(Spec spec, PooledSession jms, MessageConsumer consumer) {
            this.spec = spec;
            this.jms = jms;
            this.consumer = consumer;
        }

        @Override
        public void onMessage(Message message) {
            if (closed) {
                return;
            }
            Optional<Disposition> outcome;
            try {
                PluginMessage delivered =
                        MessageConversion.toPlugin(spec.key(), spec.clusterId(), spec.nodeId(), spec.queue(), message);
                outcome = handlers.deliver(spec.pluginId(), delivered);
            } catch (Exception | LinkageError e) {
                // The message's content is never logged: it is production payload.
                log.warn(
                        "Plugin {} failed on a message from {} ({}): {}",
                        spec.pluginId(),
                        spec.queue(),
                        spec.key(),
                        e.toString());
                outcome = Optional.of(Disposition.REJECT);
            }
            if (outcome.isEmpty()) {
                // The plugin is not receiving (it is being stopped); leave the message unsettled so
                // closing this drain returns it to the broker.
                return;
            }
            try {
                if (spec.mode() == RegistrationMode.TAP || outcome.get() == Disposition.ACCEPT) {
                    message.acknowledge();
                } else {
                    jms.session().recover();
                }
            } catch (JMSException e) {
                log.warn("Could not settle a message from {} on {}: {}", spec.queue(), spec.nodeName(), e.getMessage());
            }
        }

        /**
         * Close the consumer (which waits for a delivery in progress), hand back to the broker
         * whatever it received and did not settle, then return the session.
         *
         * <p>The recover is not optional. Closing a pooled session returns it to the pool rather than
         * ending it, and a {@code CLIENT_ACKNOWLEDGE} session keeps what it was delivered and never
         * acknowledged — so without this, those messages would stay held by an idle pooled session
         * instead of being redelivered to the next consumer.
         */
        void close() {
            closed = true;
            try {
                consumer.close();
            } catch (JMSException e) {
                log.debug("Closing a plugin drain on {}: {}", spec.nodeName(), e.getMessage());
            }
            try {
                jms.session().recover();
            } catch (JMSException e) {
                log.debug(
                        "Returning unsettled messages from a plugin drain on {}: {}", spec.nodeName(), e.getMessage());
            }
            jms.close();
        }

        /** The connection is already dead; release what is left without waiting on it. */
        void abandon() {
            closed = true;
            jms.close();
        }
    }
}
