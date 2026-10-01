package io.github.sudoitir.artemisstudio.kernel.stream.web;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.stream.EventReplay;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.kernel.stream.StreamTopicRegistry;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/stream?clusterId={uuid}&topics={csv}} — the single
 * multiplexed stream per cluster (ADR-0003, ADR-0018, ADR-0027). No timeout;
 * {@link SseHub}'s heartbeat keeps it open, and a first ping goes out on connect so the
 * client's {@code onopen} does not wait for it. {@code X-Accel-Buffering: no} tells
 * proxies not to buffer it.
 *
 * <p>The recognised topics are those the enabled modules declare (ADR-0070); a
 * topic of a disabled or unknown module is ignored. Signal topics carry a
 * {@code {topic,clusterId,ts}} envelope; a data-carrying topic with an
 * {@link EventReplay} gets a bounded replay of what a client presenting its last
 * event id missed before live delivery resumes, whichever replica serves it. The id comes as the
 * {@code lastEventId} query parameter, which the client sends itself on every connection, or as
 * {@code Last-Event-ID}. Live frames that arrive during the replay are held and follow it, without
 * repeating a replayed id; a replay that hit its cap is followed by {@code resync}. A replica that
 * is draining answers 503 so the client retries elsewhere.
 */
@RestController
public class StreamController {

    private static final int REPLAY_CAP = 500;

    private final SseHub hub;
    private final ClusterAccessGuard clusterAccess;
    private final StreamTopicRegistry topics;
    private final ReplicaRegistry replica;

    public StreamController(
            SseHub hub, ClusterAccessGuard clusterAccess, StreamTopicRegistry topics, ReplicaRegistry replica) {
        this.hub = hub;
        this.clusterAccess = clusterAccess;
        this.topics = topics;
        this.replica = replica;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam UUID clusterId,
            @RequestParam(defaultValue = "topology,health,queues") String topics,
            @RequestParam(name = "lastEventId", required = false) Long lastEventIdParam,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventIdHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (replica.state() == ReplicaRegistry.State.DRAINING || replica.state() == ReplicaRegistry.State.STOPPED) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This replica is shutting down");
        }
        Long lastEventId = lastEventIdParam != null ? lastEventIdParam : lastEventIdHeader;
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        response.setHeader("X-Accel-Buffering", "no");

        Set<String> known = this.topics.known();
        Set<String> wanted = Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(known::contains)
                .collect(Collectors.toUnmodifiableSet());

        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber =
                Subscriber.of(emitter, wanted.isEmpty() ? this.topics.defaultTopics() : wanted, request);
        if (lastEventId != null) {
            subscriber.buffer();
        }
        hub.register(clusterId, subscriber);

        emitter.onCompletion(() -> hub.remove(clusterId, subscriber));
        emitter.onTimeout(() -> hub.remove(clusterId, subscriber));
        emitter.onError(e -> hub.remove(clusterId, subscriber));
        hub.greet(subscriber);

        if (lastEventId != null) {
            Map<String, Long> replayed = new HashMap<>();
            try {
                this.topics.replays().forEach((topic, replay) -> {
                    if (subscriber.wants(topic)) {
                        replayed.put(topic, replay(subscriber, topic, replay, clusterId, lastEventId));
                    }
                });
            } catch (RuntimeException e) {
                hub.remove(clusterId, subscriber);
                throw e;
            }
            hub.release(subscriber, replayed);
        }
        return emitter;
    }

    /** Send what {@code subscriber} missed on {@code topic}; returns the highest id it has now received. */
    private long replay(Subscriber subscriber, String topic, EventReplay replay, UUID clusterId, long lastEventId) {
        List<EventReplay.Replayed> missed = replay.since(clusterId, lastEventId, REPLAY_CAP);
        long upTo = lastEventId;
        for (EventReplay.Replayed event : missed) {
            hub.sendTo(subscriber, topic, event.data(), event.id());
            upTo = Math.max(upTo, Long.parseLong(event.id()));
        }
        if (missed.size() >= REPLAY_CAP) {
            hub.sendTo(subscriber, SseHub.RESYNC, Instant.now().toEpochMilli(), null);
        }
        return upTo;
    }
}
