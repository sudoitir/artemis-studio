package io.github.sudoitir.artemisstudio.sse;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Collapses a burst of "this resource view is stale" nudges into at most one
 * signal per {@code (cluster, topic)} per coalescing window (ADR-0027, D11). The
 * {@code consumers} / {@code sessions} / {@code connections} / {@code queues}
 * views are served by live per-node broker reads, so an uncoalesced chatty
 * broker would turn push into a self-inflicted DoS.
 */
@Component
public class TopicCoalescer {

    private final SseHub hub;
    private final long windowMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-coalescer");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Boolean> pending = new ConcurrentHashMap<>();

    public TopicCoalescer(SseHub hub, ArtemisStudioProperties properties) {
        this.hub = hub;
        this.windowMillis = Math.max(1, properties.events().coalesceWindowMillis());
    }

    /** Nudge a topic. The first touch in a window schedules the signal; later touches are absorbed. */
    public void touch(UUID clusterId, String topic) {
        if (topic == null) {
            return;
        }
        String key = clusterId + "|" + topic;
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) {
            return; // already scheduled for this window
        }
        scheduler.schedule(
                () -> {
                    pending.remove(key);
                    hub.publish(clusterId, topic);
                },
                windowMillis,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
