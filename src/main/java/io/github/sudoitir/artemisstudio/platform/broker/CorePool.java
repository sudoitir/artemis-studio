package io.github.sudoitir.artemisstudio.platform.broker;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Pools Core connections per {@code (clusterId, coreUrl)} (ADR-0031),
 * replacing the connect-per-call pattern {@link io.github.sudoitir.artemisstudio.platform.broker.CoreMessageTransport}
 * used before the request-reply sampler made per-call connect-and-tear-down
 * too expensive to run every few seconds across every traced address.
 *
 * <p>Every borrowed {@link Session} is transacted-free
 * ({@code Session.AUTO_ACKNOWLEDGE}), matching what both the transport and the
 * sampler need. A pool is built once per key and kept until {@link #forget} (on
 * cluster removal, mirroring {@link CoreSubscriptionManager#forget}) or
 * {@link #shutdown} closes it.
 */
@Component
@RequiredArgsConstructor
public class CorePool {

    private final CoreConnectionFactory connectionFactory;

    private final Map<String, JmsPoolConnectionFactory> pools = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> keysByCluster = new ConcurrentHashMap<>();

    /** A pooled {@link Connection} + a fresh {@link Session} on it; closing returns both to the pool. */
    public PooledSession borrow(UUID clusterId, String coreUrl, CoreConnectionSettings settings) throws JMSException {
        return borrow(clusterId, coreUrl, settings, Session.AUTO_ACKNOWLEDGE);
    }

    /**
     * As {@link #borrow(UUID, String, CoreConnectionSettings)}, with the session's
     * acknowledge mode chosen by the caller. Message capture borrows
     * {@code CLIENT_ACKNOWLEDGE} so a batch is acknowledged only once its rows are
     * committed to Postgres — an auto-acknowledged consumer would lose whatever was
     * in flight when Studio stopped, which is exactly the loss capture exists to end.
     */
    public PooledSession borrow(UUID clusterId, String coreUrl, CoreConnectionSettings settings, int acknowledgeMode)
            throws JMSException {
        return borrow(clusterId, coreUrl, settings, acknowledgeMode, OPERATOR, OPERATOR_SESSIONS);
    }

    /**
     * A session for a capture drain, which holds it for as long as the tap runs. Drains borrow
     * from their own pool, so however many taps a node carries, an operator's browse or send
     * never waits for a session one of them is holding (core-transport spec).
     */
    public PooledSession borrowForCapture(UUID clusterId, String coreUrl, CoreConnectionSettings settings)
            throws JMSException {
        return borrow(clusterId, coreUrl, settings, Session.CLIENT_ACKNOWLEDGE, CAPTURE, CAPTURE_SESSIONS);
    }

    private static final String OPERATOR = "";
    private static final String CAPTURE = "|capture";

    /** Short-lived browse, send and sampling sessions per node. */
    private static final int OPERATOR_SESSIONS = 8;

    /** Long-lived capture drains per node: one session per tap. */
    private static final int CAPTURE_SESSIONS = 256;

    private PooledSession borrow(
            UUID clusterId,
            String coreUrl,
            CoreConnectionSettings settings,
            int acknowledgeMode,
            String purpose,
            int maxSessions)
            throws JMSException {
        String key = clusterId + "|" + coreUrl + purpose;
        JmsPoolConnectionFactory pool =
                pools.computeIfAbsent(key, k -> buildPool(clusterId, coreUrl, settings, key, maxSessions));
        Connection connection = settings.hasCredentials()
                ? pool.createConnection(settings.username(), settings.password())
                : pool.createConnection();
        connection.start();
        Session session = connection.createSession(false, acknowledgeMode);
        return new PooledSession(connection, session);
    }

    private JmsPoolConnectionFactory buildPool(
            UUID clusterId, String coreUrl, CoreConnectionSettings settings, String key, int maxSessions) {
        ActiveMQConnectionFactory delegate = connectionFactory.build(settings, coreUrl);
        JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
        pool.setConnectionFactory(delegate);
        pool.setMaxConnections(1);
        pool.setMaxSessionsPerConnection(maxSessions);
        keysByCluster
                .computeIfAbsent(clusterId, k -> ConcurrentHashMap.newKeySet())
                .add(key);
        return pool;
    }

    /** How many Core connections are open for a cluster, across its node pools. */
    public int connections(UUID clusterId) {
        return keysByCluster.getOrDefault(clusterId, java.util.Set.of()).stream()
                .map(pools::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(JmsPoolConnectionFactory::getNumConnections)
                .sum();
    }

    /** Closes and drops every pool for a removed cluster. Wired into {@code ClusterService.delete}. */
    public void forget(UUID clusterId) {
        Set<String> keys = keysByCluster.remove(clusterId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            JmsPoolConnectionFactory pool = pools.remove(key);
            if (pool != null) {
                pool.stop();
            }
        }
    }

    /** Close everything this holds open. Called at its shutdown phase. */
    public void closeAll() {
        pools.values().forEach(JmsPoolConnectionFactory::stop);
        pools.clear();
        keysByCluster.clear();
    }

    /** A borrowed connection/session pair. {@link #close()} returns the connection to the pool. */
    public record PooledSession(Connection connection, Session session) implements AutoCloseable {
        @Override
        public void close() {
            try {
                session.close();
            } catch (JMSException ignored) {
                // teardown
            }
            try {
                connection.close();
            } catch (JMSException ignored) {
                // teardown — pooled close() returns it to the pool, never tears down the real socket
            }
        }
    }
}
