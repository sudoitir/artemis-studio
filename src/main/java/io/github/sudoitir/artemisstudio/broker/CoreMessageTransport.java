package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.core.CoreConnectionSettings;
import io.github.sudoitir.artemisstudio.broker.core.CorePool;
import io.github.sudoitir.artemisstudio.broker.core.CorePool.PooledSession;
import io.github.sudoitir.artemisstudio.broker.core.CoreUrl;
import jakarta.jms.BytesMessage;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.springframework.stereotype.Component;

/**
 * {@link MessageTransport} over the Core protocol client (ADR-0029). A
 * {@link QueueBrowser} is a non-destructive read of the real messages: real
 * property types, real byte bodies, no management-layer stringification, no
 * truncation.
 *
 * <ul>
 *   <li>A {@code QueueBrowser} has no server-side offset, so a page past a bounded
 *       depth ({@link MessageBrowser#BROKER_PAGE_CAP}) is served over Jolokia
 *       instead, and {@link BrowseResult#servedBy()} says so (non-negotiable #1).
 *   <li>By-id / by-filter mutations carry no payload and stay on Jolokia
 *       ({@link MessageOperations}) — no Core method here (ADR-0029, D9).
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreMessageTransport implements MessageTransport {

    private static final int MAX_CORE_DEPTH = MessageBrowser.BROKER_PAGE_CAP;

    private final BrokerConnections connections;
    private final CorePool corePool;
    private final JolokiaMessageTransport jolokiaFallback;

    @Override
    public Channel channel() {
        return Channel.CORE;
    }

    @Override
    public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
        long skip = (long) (page - 1) * size;
        if (skip + size > MAX_CORE_DEPTH) {
            // No server-side offset on a JMS browser; a deep page would walk the queue.
            return new BrowseResult(
                    jolokiaFallback.browse(target, page, size, filter).page(), Channel.JOLOKIA);
        }
        try (PooledSession jms = open(target.clusterId(), target.coreUrl())) {
            Session session = jms.session();
            Queue queue = session.createQueue(target.queueName());
            QueueBrowser browser = (filter == null || filter.isBlank())
                    ? session.createBrowser(queue)
                    : session.createBrowser(queue, filter);

            List<BrowsedMessage> rows = new ArrayList<>();
            long index = 0;
            long total = 0;
            Enumeration<?> e = browser.getEnumeration();
            while (e.hasMoreElements()) {
                Message m = (Message) e.nextElement();
                total++;
                if (index >= skip && rows.size() < size) {
                    rows.add(toBrowsed(m));
                }
                index++;
            }
            return new BrowseResult(new BrowsePage(List.copyOf(rows), total), Channel.CORE);
        } catch (JMSException ex) {
            log.debug("Core browse of {} failed, falling back to Jolokia: {}", target.queueName(), ex.getMessage());
            return new BrowseResult(
                    jolokiaFallback.browse(target, page, size, filter).page(), Channel.JOLOKIA);
        }
    }

    @Override
    public void send(TransportTarget target, SendSpec spec) {
        try (PooledSession jms = open(target.clusterId(), target.coreUrl())) {
            Session session = jms.session();
            Queue queue = session.createQueue(target.address());
            Message message =
                    spec.bodyBase64() ? bytesMessage(session, spec.body()) : session.createTextMessage(spec.body());
            applyProperties(message, spec.headers());
            applyProperties(message, spec.properties());
            int deliveryMode = spec.durable() ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT;
            session.createProducer(queue).send(message, deliveryMode, Message.DEFAULT_PRIORITY, 0L);
        } catch (JMSException ex) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "Core send failed: " + ex.getMessage());
        }
    }

    // ---- helpers -------------------------------------------------------

    private BytesMessage bytesMessage(Session session, String base64) throws JMSException {
        BytesMessage message = session.createBytesMessage();
        message.writeBytes(Base64.getDecoder().decode(base64 == null ? "" : base64));
        return message;
    }

    private static void applyProperties(Message message, Map<String, Object> props) throws JMSException {
        if (props == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();
            switch (v) {
                case null -> {
                    /* skip */
                }
                case String s -> message.setStringProperty(key, s);
                case Boolean b -> message.setBooleanProperty(key, b);
                case Integer i -> message.setIntProperty(key, i);
                case Long l -> message.setLongProperty(key, l);
                case Double d -> message.setDoubleProperty(key, d);
                case Number n -> message.setStringProperty(key, n.toString());
                default -> message.setStringProperty(key, v.toString());
            }
        }
    }

    private static BrowsedMessage toBrowsed(Message m) throws JMSException {
        String body;
        BodyEncoding encoding;
        if (m instanceof TextMessage text) {
            body = text.getText();
            encoding = BodyEncoding.TEXT;
        } else if (m instanceof BytesMessage bytes) {
            bytes.reset();
            long len = bytes.getBodyLength();
            byte[] raw = new byte[(int) Math.min(len, Integer.MAX_VALUE)];
            bytes.readBytes(raw);
            body = Base64.getEncoder().encodeToString(raw);
            encoding = BodyEncoding.BASE64;
        } else {
            body = null;
            encoding = BodyEncoding.TEXT;
        }

        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> bools = new LinkedHashMap<>();
        Enumeration<?> names = m.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            Object v = m.getObjectProperty(name);
            switch (v) {
                case Boolean b -> bools.put(name, b);
                case Integer i -> ints.put(name, i.longValue());
                case Long l -> longs.put(name, l);
                case Double d -> doubles.put(name, d);
                case Float f -> doubles.put(name, f.doubleValue());
                case null -> {
                    /* skip */
                }
                default -> strings.put(name, v.toString());
            }
        }

        return new BrowsedMessage(
                coreMessageId(m),
                jmsTypeInt(m),
                m.getJMSDeliveryMode() == DeliveryMode.PERSISTENT,
                m.getJMSPriority(),
                m.getJMSTimestamp(),
                m.getJMSExpiration(),
                bodyByteLength(body, encoding),
                m.getStringProperty("_AMQ_GROUP_ID"),
                m.getJMSCorrelationID(),
                m.getJMSReplyTo() != null ? m.getJMSReplyTo().toString() : null,
                blankToNull(stringProp(m, "_AMQ_VALIDATED_USER")),
                body,
                encoding,
                m.getJMSType(),
                false, // Core does not truncate
                null,
                strings,
                ints,
                longs,
                doubles,
                bools);
    }

    private static long coreMessageId(Message m) {
        try {
            if (m instanceof ActiveMQMessage amq) {
                return amq.getCoreMessage().getMessageID();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 0L;
    }

    private static int jmsTypeInt(Message m) throws JMSException {
        if (m instanceof TextMessage) {
            return 3;
        }
        if (m instanceof BytesMessage) {
            return 4;
        }
        return 0;
    }

    private static long bodyByteLength(String body, BodyEncoding encoding) {
        if (body == null) {
            return 0;
        }
        return encoding == BodyEncoding.BASE64
                ? Base64.getDecoder().decode(body).length
                : body.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String stringProp(Message m, String name) {
        try {
            return m.getStringProperty(name);
        } catch (JMSException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private PooledSession open(UUID clusterId, String coreUrl) {
        String dialable = CoreUrl.dialable(coreUrl);
        if (dialable == null) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "No Core URL for this node.");
        }
        CoreConnectionSettings settings = connections.coreSettingsFor(clusterId);
        try {
            return corePool.borrow(clusterId, dialable, settings);
        } catch (JMSException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Could not open a Core session: " + e.getMessage());
        }
    }
}
