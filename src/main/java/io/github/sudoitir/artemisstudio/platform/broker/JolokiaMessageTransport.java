package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link MessageTransport} over the management channel (ADR-0029). A thin adapter
 * around the existing {@link MessageBrowser} and {@link MessageOperations} — no
 * behaviour change; this is the fallback and stays the well-tested path.
 */
@Component
@RequiredArgsConstructor
public class JolokiaMessageTransport implements MessageTransport {

    /** The broker's own names for the headers, and for the two group properties a client may set. */
    /** The supported headers, under the broker's own names. */
    private static final Map<String, String> HEADER_NAMES = Map.of(
            "correlationId", "JMSCorrelationID",
            "type", "JMSType",
            "replyTo", "JMSReplyTo",
            "groupId", "_AMQ_GROUP_ID",
            "groupSeq", "_AMQ_GROUP_SEQUENCE");

    /**
     * The JMS-defined properties a client may set, under the broker's own names, as the Core client
     * maps them. Every other application property keeps its name: a property called {@code type} or
     * {@code groupId} is an application property, not a header.
     */
    private static final Map<String, String> PROPERTY_NAMES =
            Map.of("JMSXGroupID", "_AMQ_GROUP_ID", "JMSXGroupSeq", "_AMQ_GROUP_SEQUENCE");

    private final BrokerConnections connections;
    private final MessageBrowser messageBrowser;
    private final MessageOperations messageOps;

    /**
     * The properties as {@code sendMessage} takes them: a map of strings, under the broker's own
     * names. The operation is {@code Map<String,String>} and Artemis offers no typed variant, so an
     * int, long, double or boolean property arrives as its text (a JSON number would reach the
     * broker as a Long it cannot cast); a null value is left out, as the Core path leaves it out.
     */
    static Map<String, Object> brokerProperties(Map<String, Object> properties) {
        return renamed(properties, PROPERTY_NAMES);
    }

    /** The headers as {@code sendMessage} takes them: strings under the broker's names for them. */
    static Map<String, Object> brokerHeaders(Map<String, Object> headers) {
        return renamed(headers, HEADER_NAMES);
    }

    private static Map<String, Object> renamed(Map<String, Object> values, Map<String, String> names) {
        Map<String, Object> named = new HashMap<>();
        values.forEach((name, value) -> {
            if (value != null) {
                named.put(names.getOrDefault(name, name), value.toString());
            }
        });
        return named;
    }

    @Override
    public Channel channel() {
        return Channel.JOLOKIA;
    }

    @Override
    public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
        JolokiaBrokerClient client = connections.forCluster(target.clusterId(), target.jolokiaUrl());
        String queueMbean = BrokerMBeans.queue(
                client.resolveBrokerObjectName(), target.address(), target.queueName(), target.routingType());
        return new BrowseResult(messageBrowser.browse(client, queueMbean, page, size, filter), Channel.JOLOKIA);
    }

    @Override
    public void send(TransportTarget target, SendSpec spec) {
        JolokiaBrokerClient client = connections.forCluster(target.clusterId(), target.jolokiaUrl());
        String addressMbean = BrokerMBeans.address(client.resolveBrokerObjectName(), target.address());
        // Headers last: a header the operator set wins over a property that maps to the same name.
        Map<String, Object> merged = new HashMap<>(brokerProperties(spec.properties()));
        merged.putAll(brokerHeaders(spec.headers()));
        // The broker makes the sender the message's validated user, so it takes the account Studio
        // manages it with; a broker without security takes an empty one.
        BrokerConnectionSettings login = connections.settingsFor(target.clusterId());
        boolean named = login != null && login.hasCredentials();
        messageOps.send(
                client,
                addressMbean,
                new MessageOperations.Outgoing(merged, spec.type(), spec.body(), spec.durable()),
                new MessageOperations.Sender(
                        named ? login.username() : "", named && login.password() != null ? login.password() : ""));
    }
}
