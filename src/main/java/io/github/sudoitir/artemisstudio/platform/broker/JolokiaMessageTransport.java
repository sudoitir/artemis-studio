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
    private static final Map<String, String> CORE_NAMES = Map.of(
            "correlationId", "JMSCorrelationID",
            "type", "JMSType",
            "replyTo", "JMSReplyTo",
            "groupId", "_AMQ_GROUP_ID",
            "groupSeq", "_AMQ_GROUP_SEQUENCE",
            "JMSXGroupID", "_AMQ_GROUP_ID",
            "JMSXGroupSeq", "_AMQ_GROUP_SEQUENCE");

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
        Map<String, Object> named = new HashMap<>();
        properties.forEach((name, value) -> {
            if (value != null) {
                named.put(CORE_NAMES.getOrDefault(name, name), value.toString());
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
        Map<String, Object> merged = new HashMap<>(brokerProperties(spec.headers()));
        merged.putAll(brokerProperties(spec.properties()));
        // The broker makes the sender the message's validated user, so it takes the account Studio
        // manages it with; a broker without security takes an empty one.
        BrokerConnectionSettings login = connections.settingsFor(target.clusterId());
        boolean named = login != null && login.hasCredentials();
        messageOps.send(
                client,
                addressMbean,
                merged,
                spec.type(),
                spec.body(),
                spec.durable(),
                named ? login.username() : "",
                named && login.password() != null ? login.password() : "");
    }
}
