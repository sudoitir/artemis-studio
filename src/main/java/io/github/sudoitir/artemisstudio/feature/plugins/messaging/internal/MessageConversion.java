package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.PluginMessage;
import jakarta.jms.BytesMessage;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Turns a received JMS message into what a plugin is handed. */
final class MessageConversion {

    private MessageConversion() {}

    static PluginMessage toPlugin(String key, UUID clusterId, UUID nodeId, String queue, Message m)
            throws JMSException {
        byte[] body = new byte[0];
        boolean text = false;
        if (m instanceof TextMessage t) {
            String s = t.getText();
            body = s == null ? body : s.getBytes(StandardCharsets.UTF_8);
            text = true;
        } else if (m instanceof BytesMessage b) {
            body = new byte[(int) b.getBodyLength()];
            b.readBytes(body);
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        putIfSet(headers, "messageId", m.getJMSMessageID());
        putIfSet(headers, "correlationId", m.getJMSCorrelationID());
        putIfSet(headers, "type", m.getJMSType());
        headers.put("priority", m.getJMSPriority());
        headers.put("timestamp", m.getJMSTimestamp());
        if (m.getJMSExpiration() > 0) {
            headers.put("expiration", m.getJMSExpiration());
        }
        headers.put("durable", m.getJMSDeliveryMode() == DeliveryMode.PERSISTENT);
        if (m.getJMSReplyTo() != null) {
            headers.put("replyTo", m.getJMSReplyTo().toString());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Enumeration<String> names = m.getPropertyNames();
        for (String name : Collections.list(names)) {
            if (!name.startsWith("_AMQ") && !name.startsWith("__AMQ") && !name.startsWith("JMSX")) {
                properties.put(name, m.getObjectProperty(name));
            }
        }
        int deliveryCount = m.propertyExists("JMSXDeliveryCount") ? m.getIntProperty("JMSXDeliveryCount") : 1;
        return new PluginMessage(
                key,
                clusterId,
                nodeId,
                queue,
                body,
                text,
                Collections.unmodifiableMap(headers),
                Collections.unmodifiableMap(properties),
                deliveryCount);
    }

    private static void putIfSet(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
