package io.github.sudoitir.artemisstudio.platform.broker;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/**
 * Wraps a Core operation in a {@code studio.broker.core} observation, which is both a timer and
 * a span. Artemis's Core client has no instrumentation of its own, so the transport's choke
 * points call this. The tags are the {@code operation} and the node's {@code host:port}
 * ({@link NodeAddress}); message content, headers and addresses are never recorded.
 */
@Component
public class CoreObservations {

    public static final String NAME = "studio.broker.core";

    private final ObservationRegistry registry;

    public CoreObservations(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** Records nothing; for a caller built by hand. */
    public static CoreObservations none() {
        return new CoreObservations(ObservationRegistry.NOOP);
    }

    public <T, E extends Throwable> T observe(String operation, String nodeUrl, Observation.CheckedCallable<T, E> call)
            throws E {
        return observation(operation, nodeUrl).observeChecked(call);
    }

    public <E extends Throwable> void run(String operation, String nodeUrl, Observation.CheckedRunnable<E> call)
            throws E {
        observation(operation, nodeUrl).observeChecked(call);
    }

    private Observation observation(String operation, String nodeUrl) {
        return Observation.createNotStarted(NAME, registry)
                .contextualName("core " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("node", NodeAddress.hostPort(nodeUrl));
    }
}
