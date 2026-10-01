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
    private final CoreObservations observations;

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
    public Session open(UUID clusterId, String url, CoreConnectionSettings settings) throws ActiveMQException {
        return observations.observe("relay.open", url, () -> openSession(clusterId, url, settings));
    }

    private Session openSession(UUID clusterId, String url, CoreConnectionSettings settings) throws ActiveMQException {
        // Discovery stores a broker-advertised connector as a bare host:port, which the Core client
        // cannot dial ("Schema <host> not found"). Every relay call arrives here, so it is the one
        // place that has to say tcp://.
        String coreUrl = CoreUrl.dialable(url);
        if (coreUrl == null) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This node has no Core URL, so messages cannot be relayed through it. Add one to the node, or"
                            + " expose a CORE acceptor on the broker.");
        }
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
        try {
            session.start();
        } catch (ActiveMQException | RuntimeException failure) {
            try {
                session.close();
            } catch (ActiveMQException _) {
                // teardown; the failure that got us here is the one to report
            }
            throw failure;
        }
        return new Session(session, observations, url);
    }

    /** A node's Core endpoint, with the cluster's connection settings. */
    public record Endpoint(UUID clusterId, String coreUrl, CoreConnectionSettings settings) {}

    /**
     * A relay between two nodes along {@code route}: a transacted session on each. Close it when the
     * run ends or either node fails; the broker rolls back whatever it had not committed.
     *
     * @throws BrokerConnectionException when either node cannot be reached
     */
    public RelayLink link(Endpoint source, Endpoint target, RelayLink.Route route) {
        Session from = null;
        try {
            from = open(source.clusterId(), source.coreUrl(), source.settings());
            Session to = open(target.clusterId(), target.coreUrl(), target.settings());
            return new RelayLink(from, to, route);
        } catch (ActiveMQException e) {
            if (from != null) {
                from.close();
            }
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Could not open a relay session: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (from != null) {
                from.close();
            }
            throw e;
        }
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

    /** The clusters this holds relay connections for. */
    Set<UUID> clusterIds() {
        return Set.copyOf(keysByCluster.keySet());
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

        private final ClientSession clientSession;
        private ClientProducer producer;

        private final CoreObservations observations;
        private final String url;

        Session(ClientSession clientSession, CoreObservations observations, String url) {
            this.clientSession = clientSession;
            this.observations = observations;
            this.url = url;
        }

        /** A destructive consumer; each message it gives is acknowledged by {@link #acknowledge} and {@link #commit}. */
        public ClientConsumer receiver(String queue) throws ActiveMQException {
            return clientSession.createConsumer(queue);
        }

        /** A browse-only consumer: the queue is left as it is. */
        public ClientConsumer browser(String queue, String filter) throws ActiveMQException {
            return clientSession.createConsumer(
                    SimpleString.of(queue), filter == null ? null : SimpleString.of(filter), true);
        }

        /**
         * Acknowledge this one received message within the transaction; it leaves the queue on
         * {@link #commit()}. Individual, because a Core acknowledgement is otherwise cumulative: it
         * would also acknowledge every message received before it.
         */
        public void acknowledge(ClientMessage message) throws ActiveMQException {
            message.individualAcknowledge();
        }

        /** Send to exactly {@code queue} on {@code address} (its FQQN), within the transaction. */
        public void send(String address, String queue, ClientMessage message) throws ActiveMQException {
            if (producer == null) {
                producer = clientSession.createProducer();
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
            observations.run("relay.commit", url, clientSession::commit);
        }

        public void rollback() throws ActiveMQException {
            clientSession.rollback();
        }

        @Override
        public void close() {
            try {
                clientSession.close();
            } catch (ActiveMQException | RuntimeException e) {
                log.debug("Relay session close failed", e);
            }
        }
    }
}
