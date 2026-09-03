package io.github.sudoitir.artemisstudio.spike;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 spike — NOT a real test. Run by hand against the dev compose pair
 * (`just up`) to capture the real {@code _AMQ_NotifType} values and headers that
 * land on {@code activemq.notifications}. The findings are written up in
 * {@code docs/broker-management-notes.md}; this class stays in the tree as the
 * reproducible receipt.
 *
 * <p>Run: {@code ./mvnw test -Dtest=NotificationSpikeIT
 * -DargLine='-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition'}
 *
 * <p>Poll-based on purpose: a JMS {@code MessageListener} on the notification
 * consumer deadlocks against {@code close()} with the 2.56 client / 2.44 broker
 * pairing this repo pins.
 */
@Disabled("Phase 0 spike — needs a running broker from `just up`")
class NotificationSpikeIT {

    // useTopologyForLoadBalancing=false: the broker pushes its cluster topology to
    // CORE clients, and it advertises the *connector* hosts (artemis-primary:61616,
    // artemis-backup:61616 — Docker service names). A client outside the compose
    // network cannot resolve those and every blocking call then fails with
    // AMQ219016. Studio's Phase 4 Core client will hit the same wall unless it runs
    // on the broker network or pins connections the same way.
    private static final String URL = "tcp://localhost:61616?useTopologyForLoadBalancing=false";
    private static final String USER = "artemis";
    private static final String PASS = "artemis";
    private static final String NOTIF_ADDRESS = "activemq.notifications";
    private static final long DRAIN_MILLIS = 12_000;

    @Test
    void captureNotificationCatalogue() throws Exception {
        var factory = new ActiveMQConnectionFactory(URL, USER, PASS);
        factory.setInitialConnectAttempts(1);
        factory.setReconnectAttempts(0);
        var blocks = new ArrayList<String>();

        Connection listenerConn = factory.createConnection(USER, PASS);
        listenerConn.start();
        Session listenerSession = listenerConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic notifications = listenerSession.createTopic(NOTIF_ADDRESS);
        MessageConsumer notifConsumer = listenerSession.createConsumer(notifications);

        // Provoke traffic ~1.5s in, on a daemon thread, so binding/consumer/
        // connection/session events interleave with the drain loop below.
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
            blocks.add(render(msg));
        }

        closeQuietly(notifConsumer);
        closeQuietly(listenerSession);
        closeQuietly(listenerConn);
        closeQuietly(factory);

        blocks.forEach(System.out::print);
        System.out.println("\n================ _AMQ_NotifType catalogue seen ================");
        blocks.stream()
                .flatMap(String::lines)
                .filter(l -> l.contains("_AMQ_NotifType ="))
                .map(l -> l.substring(l.indexOf('=') + 1).trim())
                .distinct()
                .sorted()
                .forEach(System.out::println);
        System.out.println("total notification messages: " + blocks.size());
    }

    private static String render(Message msg) {
        var sb = new StringBuilder("\n--- notification ---\n");
        try {
            List<String> names = new ArrayList<>();
            Enumeration<?> e = msg.getPropertyNames();
            while (e.hasMoreElements()) {
                names.add((String) e.nextElement());
            }
            names.sort(String::compareTo);
            for (String name : names) {
                sb.append("  ")
                        .append(name)
                        .append(" = ")
                        .append(msg.getObjectProperty(name))
                        .append('\n');
            }
        } catch (Exception ex) {
            sb.append("  <error reading properties: ").append(ex).append(">\n");
        }
        return sb.toString();
    }

    /** Create a connection, address, queue, consumer; send/receive; tear it all down. */
    private void provoke(ActiveMQConnectionFactory factory) throws Exception {
        String queueName = "spike.probe." + System.currentTimeMillis();
        Connection conn = factory.createConnection(USER, PASS);
        conn.start();
        Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        var queue = session.createQueue(queueName); // auto-created address + queue
        MessageProducer producer = session.createProducer(queue);
        MessageConsumer consumer = session.createConsumer(queue);
        producer.send(session.createTextMessage("hello"));
        TextMessage received = (TextMessage) consumer.receive(2_000);
        System.out.println("provoke: received = " + (received == null ? null : received.getText()));
        closeQuietly(consumer);
        closeQuietly(producer);
        closeQuietly(session);
        closeQuietly(conn); // -> CONNECTION_DESTROYED / SESSION_CLOSED
    }

    private interface Block {
        void run() throws Exception;
    }

    private static void quietly(Block b) {
        try {
            b.run();
        } catch (Exception e) {
            System.out.println("provoke failed: " + e);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // spike teardown
        }
    }
}
