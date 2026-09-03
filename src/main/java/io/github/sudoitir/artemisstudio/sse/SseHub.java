package io.github.sudoitir.artemisstudio.sse;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The in-memory SSE fan-out (ADR-0018). One {@code Set<Subscriber>} per cluster;
 * {@link #publish} sends a tiny change-signal event to every subscriber that
 * asked for the topic. Events carry no data — the client refetches the matching
 * query key — so a broker that changes nothing produces only the heartbeat.
 *
 * <p>The registry is per-instance and does not survive a restart; multi-instance
 * fan-out is post-MVP (matches {@code docs/architecture.md}).
 */
@Component
@Slf4j
public class SseHub {

    private final Map<UUID, Set<Subscriber>> byCluster = new ConcurrentHashMap<>();

    public void register(UUID clusterId, Subscriber subscriber) {
        byCluster.computeIfAbsent(clusterId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
    }

    public void remove(UUID clusterId, Subscriber subscriber) {
        Set<Subscriber> set = byCluster.get(clusterId);
        if (set != null) {
            set.remove(subscriber);
        }
    }

    /** Send a `{topic,clusterId,ts}` signal to every subscriber of {@code topic} on this cluster. */
    public void publish(UUID clusterId, String topic) {
        Set<Subscriber> set = byCluster.get(clusterId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "topic",
                topic,
                "clusterId",
                clusterId.toString(),
                "ts",
                Instant.now().toEpochMilli());
        for (Subscriber s : set) {
            if (s.wants(topic)) {
                trySend(clusterId, s, topic, payload);
            }
        }
    }

    /** Keep idle streams open through proxies. A comment, not an event. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        byCluster.forEach((clusterId, set) -> set.forEach(s -> {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException e) {
                drop(clusterId, s, e);
            }
        }));
    }

    private void trySend(UUID clusterId, Subscriber s, String event, Object data) {
        try {
            s.emitter().send(SseEmitter.event().name(event).data(data));
        } catch (IOException | RuntimeException e) {
            drop(clusterId, s, e);
        }
    }

    private void drop(UUID clusterId, Subscriber s, Exception cause) {
        remove(clusterId, s);
        try {
            s.emitter().completeWithError(cause);
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    int subscriberCount(UUID clusterId) {
        Set<Subscriber> set = byCluster.get(clusterId);
        return set == null ? 0 : set.size();
    }
}
