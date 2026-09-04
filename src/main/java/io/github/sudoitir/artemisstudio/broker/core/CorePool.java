package io.github.sudoitir.artemisstudio.broker.core;

import jakarta.annotation.PreDestroy;
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
 * replacing the connect-per-call pattern {@link io.github.sudoitir.artemisstudio.broker.CoreMessageTransport}
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
        String key = clusterId + "|" + coreUrl;
        JmsPoolConnectionFactory pool = pools.computeIfAbsent(key, k -> buildPool(clusterId, coreUrl, settings));
        Connection connection = settings.hasCredentials()
                ? pool.createConnection(settings.username(), settings.password())
                : pool.createConnection();
        connection.start();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        return new PooledSession(connection, session);
    }

    private JmsPoolConnectionFactory buildPool(UUID clusterId, String coreUrl, CoreConnectionSettings settings) {
        ActiveMQConnectionFactory delegate = connectionFactory.build(settings, coreUrl);
        JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
        pool.setConnectionFactory(delegate);
        pool.setMaxConnections(1);
        pool.setMaxSessionsPerConnection(8);
        keysByCluster
                .computeIfAbsent(clusterId, k -> ConcurrentHashMap.newKeySet())
                .add(clusterId + "|" + coreUrl);
        return pool;
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

    @PreDestroy
    void shutdown() {
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
