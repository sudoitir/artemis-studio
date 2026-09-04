package io.github.sudoitir.artemisstudio.broker;

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

    private final BrokerConnections connections;
    private final MessageBrowser messageBrowser;
    private final MessageOperations messageOps;

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
        Map<String, Object> merged = new HashMap<>();
        if (spec.headers() != null) {
            merged.putAll(spec.headers());
        }
        if (spec.properties() != null) {
            merged.putAll(spec.properties());
        }
        messageOps.send(client, addressMbean, merged, spec.type(), spec.body(), spec.durable());
    }
}
