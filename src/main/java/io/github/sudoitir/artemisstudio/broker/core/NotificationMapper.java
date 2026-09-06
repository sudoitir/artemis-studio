package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.broker.BrokerTime;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.activemq.artemis.api.core.management.CoreNotificationType;
import org.springframework.stereotype.Component;

/**
 * Turns a JMS {@link Message} received on {@code activemq.notifications} into a
 * {@link BrokerEvent} (ADR-0026).
 *
 * <p>Everything is read from {@code _AMQ_*} object properties — a notification's
 * {@code JMSMessageID} is null (broker-management-notes §7). An unrecognised
 * {@code _AMQ_NotifType} still produces an event, typed {@code "UNKNOWN:<raw>"},
 * so a newer broker's event class is never dropped or thrown on.
 */
@Component
public class NotificationMapper {

    /**
     * A notification's timestamps come from the broker's clock; every other instant
     * Studio stores is on its own. Left unnormalised, a completed flow could
     * subtract a broker instant from a Studio instant and call the difference
     * latency (ADR-0053). Supplied lazily because the offsets are refreshed on a
     * schedule and this mapper outlives any one reading.
     */
    private final Supplier<BrokerTime> brokerTime;

    public NotificationMapper(io.github.sudoitir.artemisstudio.service.ClockOffsetService clocks) {
        this.brokerTime = clocks::brokerTime;
    }

    /** Without normalisation — for tests and for callers that have no node in hand. */
    public NotificationMapper() {
        this.brokerTime = BrokerTime::identity;
    }

    public BrokerEvent toEvent(UUID clusterId, UUID nodeId, Message message) throws JMSException {
        Map<String, Object> props = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            props.put(name, message.getObjectProperty(name));
        }

        String rawType = str(props.get("_AMQ_NotifType"));
        String type = normaliseType(rawType);
        Instant occurredAt = instant(nodeId, props.get("_AMQ_NotifTimestamp"), message.getJMSTimestamp());

        return new BrokerEvent(
                clusterId,
                nodeId,
                type,
                occurredAt,
                str(props.get("_AMQ_Address")),
                str(props.get("_AMQ_RoutingName")),
                str(props.get("_AMQ_ConsumerName")),
                str(props.get("_AMQ_SessionName")),
                str(props.get("_AMQ_ConnectionName")),
                str(props.get("_AMQ_RemoteAddress")),
                firstNonNull(props.get("_AMQ_ValidatedUser"), props.get("_AMQ_User")),
                props);
    }

    private static String normaliseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "UNKNOWN:";
        }
        for (CoreNotificationType known : CoreNotificationType.values()) {
            if (known.name().equals(rawType)) {
                return known.name();
            }
        }
        return "UNKNOWN:" + rawType;
    }

    private Instant instant(UUID nodeId, Object amqTimestamp, long jmsTimestamp) {
        long millis = amqTimestamp instanceof Number number ? number.longValue() : jmsTimestamp;
        return brokerTime.get().toStudioTime(nodeId, millis);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonNull(Object a, Object b) {
        Object chosen = a != null ? a : b;
        return chosen == null ? null : chosen.toString();
    }
}
