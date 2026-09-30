package io.github.sudoitir.artemisstudio.platform.broker;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Wraps a Core operation in a {@code studio.broker.core} observation, which is both a timer and
 * a span. Artemis's Core client has no instrumentation of its own, so the transport's choke
 * points call this. The tags are the {@code operation} and the {@code node}: the node's Jolokia
 * {@code host:port}, like every other Studio meter ({@link NodeAddress}), found from the Core URL
 * among the registered nodes, or the Core {@code host:port} for a node with no management address.
 * The Core address is a span-only key-value. Message content, headers and addresses are never recorded.
 */
@Component
public class CoreObservations {

    public static final String NAME = "studio.broker.core";

    /** How long the Core-to-Jolokia address map is reused before the registered nodes are read again. */
    private static final Duration REFRESH = Duration.ofSeconds(30);

    private final ObservationRegistry registry;
    private final Supplier<Map<String, String>> nodeByCore;
    private volatile Map<String, String> known = Map.of();
    private volatile long readAt = Long.MIN_VALUE;

    @Autowired
    public CoreObservations(ObservationRegistry registry, ObjectProvider<NodeDirectory> directory) {
        this.registry = registry;
        this.nodeByCore = () -> {
            NodeDirectory nodes = directory.getIfAvailable();
            Map<String, String> byCore = new HashMap<>();
            if (nodes != null) {
                nodes.nodes().stream()
                        .filter(n -> n.jolokiaUrl() != null && n.coreUrl() != null)
                        .forEach(n ->
                                byCore.put(NodeAddress.hostPort(n.coreUrl()), NodeAddress.hostPort(n.jolokiaUrl())));
            }
            return byCore;
        };
    }

    private CoreObservations(ObservationRegistry registry) {
        this.registry = registry;
        this.nodeByCore = Map::of;
    }

    /** Records nothing; for a caller built by hand. */
    public static CoreObservations none() {
        return new CoreObservations(ObservationRegistry.NOOP);
    }

    public <T, E extends Throwable> T observe(String operation, String coreUrl, Observation.CheckedCallable<T, E> call)
            throws E {
        return observation(operation, coreUrl).observeChecked(call);
    }

    public <E extends Throwable> void run(String operation, String coreUrl, Observation.CheckedRunnable<E> call)
            throws E {
        observation(operation, coreUrl).observeChecked(call);
    }

    private Observation observation(String operation, String coreUrl) {
        String core = NodeAddress.hostPort(coreUrl);
        return Observation.createNotStarted(NAME, registry)
                .contextualName("core " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("node", managementAddress(core))
                .highCardinalityKeyValue("core.address", core);
    }

    private String managementAddress(String core) {
        if (registry.isNoop()) {
            return core;
        }
        long now = System.nanoTime();
        if (readAt == Long.MIN_VALUE || now - readAt > REFRESH.toNanos()) {
            readAt = now;
            try {
                known = nodeByCore.get();
            } catch (RuntimeException _) {
                // Telemetry never fails an operation: keep the last map and look again in REFRESH.
            }
        }
        return known.getOrDefault(core, core);
    }
}
