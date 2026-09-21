package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Transacted Core-API sessions for relaying messages between two nodes (ADR-0097, core-transport
 * spec). They are built from {@link CoreConnectionFactory}, so they carry the cluster's credentials,
 * TLS and timeouts, but each node's relays share a connection of their own: a relay never holds a
 * session an operator's browse or send ({@link CorePool}) or a capture drain is waiting for.
 *
 * <p>Every session is transacted both ways: a receive is acknowledged, and a send delivered, only
 * on {@link Session#commit()}. Every blocking call, closing included, is bounded by the connection's
 * call timeout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreRelay {

    private final CoreConnectionFactory connectionFactory;

    private record Connection(ServerLocator locator, ClientSessionFactory factory) {
        boolean usable() {
            return !factory.isClosed()
                    && factory.getConnection() != null
                    && !factory.getConnection().isDestroyed();
        }

        void close() {
            factory.close();
            locator.close();
        }
    }

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> keysByCluster = new ConcurrentHashMap<>();

    /** A new transacted session on the node's relay connection, opening that connection if needed. */
    public Session open(UUID clusterId, String coreUrl, CoreConnectionSettings settings) throws ActiveMQException {
        String key = clusterId + "|" + coreUrl;
        Connection connection = connections.compute(key, (k, existing) -> {
            if (existing != null && existing.usable()) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return connect(clusterId, coreUrl, settings, k);
        });
        ClientSession session = connection
                .factory()
                .createSession(settings.username(), settings.password(), false, false, false, false, 0);
        session.start();
        return new Session(session);
    }

    private Connection connect(UUID clusterId, String coreUrl, CoreConnectionSettings settings, String key) {
        ServerLocator locator = connectionFactory.build(settings, coreUrl).getServerLocator();
        try {
            ClientSessionFactory factory = locator.createSessionFactory();
            keysByCluster
                    .computeIfAbsent(clusterId, c -> ConcurrentHashMap.newKeySet())
                    .add(key);
            return new Connection(locator, factory);
        } catch (Exception e) {
            locator.close();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Could not open a relay connection to " + coreUrl + ": " + e.getMessage(),
                    e);
        }
    }

    /** Close a removed cluster's relay connections. */
    public void forget(UUID clusterId) {
        Set<String> keys = keysByCluster.remove(clusterId);
        if (keys != null) {
            keys.forEach(key -> close(connections.remove(key)));
        }
    }

    /** Close every relay connection. Called at the Core pool's shutdown phase. */
    public void closeAll() {
        connections.values().forEach(CoreRelay::close);
        connections.clear();
        keysByCluster.clear();
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                log.debug("Relay connection close failed", e);
            }
        }
    }

    /** Large-message spool files left by a Studio that stopped mid-relay are useless now. */
    @EventListener(ApplicationReadyEvent.class)
    void sweepSpool() {
        OutboundMessages.sweep();
    }

    /** One transacted Core session on one node. Not thread-safe: one run drives it. */
    public static final class Session implements AutoCloseable {

        private final ClientSession session;
        private ClientProducer producer;

        Session(ClientSession session) {
            this.session = session;
        }

        /** A destructive consumer; each message it gives is acknowledged by {@link #acknowledge} and {@link #commit}. */
        public ClientConsumer receiver(String queue) throws ActiveMQException {
            return session.createConsumer(queue);
        }

        /** A browse-only consumer: the queue is left as it is. */
        public ClientConsumer browser(String queue, String filter) throws ActiveMQException {
            return session.createConsumer(
                    SimpleString.of(queue), filter == null ? null : SimpleString.of(filter), true);
        }

        /** Acknowledge a received message within the transaction; it leaves the queue on {@link #commit()}. */
        public void acknowledge(ClientMessage message) throws ActiveMQException {
            message.acknowledge();
        }

        /** Send to exactly {@code queue} on {@code address} (its FQQN), within the transaction. */
        public void send(String address, String queue, ClientMessage message) throws ActiveMQException {
            if (producer == null) {
                producer = session.createProducer();
            }
            producer.send(SimpleString.of(address + "::" + queue), message);
        }

        /**
         * Commit the transaction. On a target, a send whose duplicate id the broker has already seen
         * makes it refuse the <em>whole</em> transaction with
         * {@link org.apache.activemq.artemis.api.core.ActiveMQDuplicateIdException}: none of the batch
         * is delivered, including messages it had not seen. The caller rolls back and sends that batch
         * again one message per transaction, where a refusal means only that message already arrived.
         */
        public void commit() throws ActiveMQException {
            session.commit();
        }

        public void rollback() throws ActiveMQException {
            session.rollback();
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (ActiveMQException | RuntimeException e) {
                log.debug("Relay session close failed", e);
            }
        }
    }
}
