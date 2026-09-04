package io.github.sudoitir.artemisstudio.broker.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

/**
 * Was the Phase 0 {@code NotificationSpikeIT}, run by hand against {@code just up}.
 * Now a real integration test against {@link ArtemisIntegrationTest}'s container:
 * it provokes broker activity and drains {@code activemq.notifications} to confirm
 * the {@code _AMQ_NotifType} catalogue Phase 4 codes against.
 *
 * <p>Two constraints carried over from the spike, both still load-bearing:
 *
 * <ul>
 *   <li><b>Poll, never a {@code MessageListener}.</b> A JMS listener on the
 *       notification consumer deadlocks against {@code close()} with the 2.56
 *       client / 2.44 broker pairing this repo pins. The drain loop below uses
 *       {@code consumer.receive(timeout)}.
 *   <li><b>{@code useTopologyForLoadBalancing=false} + {@code reconnectAttempts=0}.</b>
 *       The broker pushes its topology to CORE clients and advertises the
 *       {@code <connector>} hosts; a client that cannot resolve those otherwise
 *       blocks on a reconnect loop.
 * </ul>
 */
class CoreEventClientTest extends ArtemisIntegrationTest {

    private static final String NOTIF_ADDRESS = "activemq.notifications";
    private static final long DRAIN_MILLIS = 12_000;

    @Test
    void notificationCatalogueIsObservable() throws Exception {
        String url = coreUrl() + "?useTopologyForLoadBalancing=false";
        var factory = new ActiveMQConnectionFactory(url, BROKER_USER, BROKER_PASSWORD);
        factory.setInitialConnectAttempts(1);
        factory.setReconnectAttempts(0);

        Set<String> typesSeen = new LinkedHashSet<>();
        List<Message> messages = new ArrayList<>();

        Connection listenerConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD);
        try {
            listenerConn.start();
            Session listenerSession = listenerConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic notifications = listenerSession.createTopic(NOTIF_ADDRESS);
            MessageConsumer notifConsumer = listenerSession.createConsumer(notifications);

            Thread provoker = new Thread(() -> quietly(() -> {
                TimeUnit.MILLISECONDS.sleep(1_500);
                provoke(factory);
            }));
            provoker.setDaemon(true);
            provoker.start();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MILLIS);
            while (System.nanoTime() < deadline) {
                Message msg = notifConsumer.receive(250);
                if (msg == null) {
                    continue;
                }
                messages.add(msg);
                Object type = msg.getObjectProperty("_AMQ_NotifType");
                if (type != null) {
                    typesSeen.add(type.toString());
                }
            }
            closeQuietly(notifConsumer);
            closeQuietly(listenerSession);
        } finally {
            closeQuietly(listenerConn);
            closeQuietly(factory);
        }

        assertThat(messages).isNotEmpty();
        // The provoke below opens a connection, a session, an auto-created queue,
        // a consumer, sends and receives, then tears it all down.
        assertThat(typesSeen).contains("BINDING_ADDED", "CONSUMER_CREATED", "CONSUMER_CLOSED", "SESSION_CREATED");
        // JMSMessageID is null on notifications — everything is read from _AMQ_* properties.
        assertThat(messages.getFirst().getJMSMessageID()).isNull();
        assertThat(messages.getFirst().getObjectProperty("_AMQ_NotifTimestamp")).isInstanceOf(Long.class);
    }

    private static void provoke(ActiveMQConnectionFactory factory) throws Exception {
        String queueName = "spike.probe." + System.currentTimeMillis();
        Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD);
        try {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var queue = session.createQueue(queueName);
            MessageProducer producer = session.createProducer(queue);
            MessageConsumer consumer = session.createConsumer(queue);
            producer.send(session.createTextMessage("hello"));
            TextMessage received = (TextMessage) consumer.receive(2_000);
            assertThat(received).isNotNull();
            closeQuietly(consumer);
            closeQuietly(producer);
            closeQuietly(session);
        } finally {
            closeQuietly(conn);
        }
    }

    @SuppressWarnings("unused")
    private static List<String> propertyNames(Message msg) throws Exception {
        List<String> names = new ArrayList<>();
        Enumeration<?> e = msg.getPropertyNames();
        while (e.hasMoreElements()) {
            names.add((String) e.nextElement());
        }
        return names;
    }

    private interface Block {
        void run() throws Exception;
    }

    private static void quietly(Block b) {
        try {
            b.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // teardown
        }
    }
}
