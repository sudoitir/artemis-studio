package io.github.sudoitir.artemisstudio.broker.core;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

/**
 * One node's subscription to {@code activemq.notifications} over the Core client
 * (ADR-0026). A virtual thread polls {@code consumer.receive(timeout)} — never a
 * {@link jakarta.jms.MessageListener}, which deadlocks against {@code close()} on
 * the pinned 2.56 client / 2.44 broker pairing — and hands each mapped
 * {@link BrokerEvent} to the sink.
 *
 * <p>On a receive failure the client records a {@link Failed} state and stops;
 * {@link CoreSubscriptionManager} owns retry with backoff.
 */
@Slf4j
public final class CoreEventClient implements AutoCloseable {

    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";
    private static final int RECEIVE_TIMEOUT_MILLIS = 250;

    /** Why a subscription is not currently established. */
    public enum Kind {
        NO_CORE_URL,
        UNREACHABLE,
        UNAUTHORIZED,
        PERMISSION_DENIED,
        TLS_FAILED,
        UNKNOWN
    }

    public sealed interface State permits State.Connected, State.Failed {
        record Connected(Instant since) implements State {}

        record Failed(Kind kind, String reason, Instant at) implements State {}
    }

    private final UUID clusterId;
    private final UUID nodeId;
    private final ActiveMQConnectionFactory factory;
    private final NotificationMapper mapper;
    private final Consumer<BrokerEvent> sink;

    private volatile boolean running;
    private volatile State state;
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private Thread drainThread;

    public CoreEventClient(
            UUID clusterId,
            UUID nodeId,
            ActiveMQConnectionFactory factory,
            NotificationMapper mapper,
            Consumer<BrokerEvent> sink) {
        this.clusterId = clusterId;
        this.nodeId = nodeId;
        this.factory = factory;
        this.mapper = mapper;
        this.sink = sink;
    }

    public void start() throws JMSException {
        connection = connectionFrom(factory);
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        consumer = session.createConsumer(session.createTopic(NOTIFICATIONS_ADDRESS));
        running = true;
        state = new State.Connected(Instant.now());
        drainThread = Thread.ofVirtual().name("core-notif-" + nodeId).start(this::drain);
    }

    private Connection connectionFrom(ActiveMQConnectionFactory factory) throws JMSException {
        return factory.getUser() != null
                ? factory.createConnection(factory.getUser(), factory.getPassword())
                : factory.createConnection();
    }

    private void drain() {
        while (running) {
            try {
                Message message = consumer.receive(RECEIVE_TIMEOUT_MILLIS);
                if (message != null) {
                    sink.accept(mapper.toEvent(clusterId, nodeId, message));
                }
            } catch (JMSException e) {
                if (running) {
                    state = new State.Failed(classify(e), e.getMessage(), Instant.now());
                    log.debug("Core notification subscription for node {} failed: {}", nodeId, e.getMessage());
                }
                return;
            } catch (RuntimeException e) {
                if (running) {
                    state = new State.Failed(Kind.UNKNOWN, e.getMessage(), Instant.now());
                    log.warn("Core notification subscription for node {} errored", nodeId, e);
                }
                return;
            }
        }
    }

    static Kind classify(Throwable e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("AMQ229213")) {
            return Kind.PERMISSION_DENIED; // missing consume|createNonDurableQueue on activemq.notifications
        }
        if (message.contains("AMQ229031") || message.toLowerCase().contains("security")) {
            return Kind.UNAUTHORIZED;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return Kind.TLS_FAILED;
            }
            if (c instanceof ConnectException || c instanceof UnknownHostException) {
                return Kind.UNREACHABLE;
            }
        }
        if (message.contains("AMQ214033") || message.contains("AMQ219016") || message.contains("AMQ219000")) {
            return Kind.UNREACHABLE;
        }
        return Kind.UNKNOWN;
    }

    public State state() {
        return state;
    }

    public UUID nodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(consumer);
        closeQuietly(session);
        closeQuietly(connection);
        try {
            factory.close();
        } catch (RuntimeException ignored) {
            // factory close is best-effort
        }
        if (drainThread != null) {
            try {
                drainThread.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // teardown
        }
    }
}
