package io.github.sudoitir.artemisstudio.kernel.stream.web;

import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.stream.EventReplay;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.kernel.stream.StreamTopicRegistry;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/stream?clusterId={uuid}&topics={csv}} — the single
 * multiplexed stream per cluster (ADR-0003, ADR-0018, ADR-0027). No timeout;
 * {@link SseHub}'s heartbeat keeps it open. {@code X-Accel-Buffering: no} tells
 * proxies not to buffer it.
 *
 * <p>The recognised topics are those the enabled modules declare (ADR-0070); a
 * topic of a disabled or unknown module is ignored. Signal topics carry a
 * {@code {topic,clusterId,ts}} envelope; a data-carrying topic with an
 * {@link EventReplay} gets a bounded replay of what a client presenting
 * {@code Last-Event-ID} missed before live delivery resumes.
 */
@RestController
public class StreamController {

    private static final int REPLAY_CAP = 500;

    private final SseHub hub;
    private final ClusterAccessGuard clusterAccess;
    private final StreamTopicRegistry topics;

    public StreamController(SseHub hub, ClusterAccessGuard clusterAccess, StreamTopicRegistry topics) {
        this.hub = hub;
        this.clusterAccess = clusterAccess;
        this.topics = topics;
    }

    @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam UUID clusterId,
            @RequestParam(defaultValue = "topology,health,queues") String topics,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletResponse response) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        response.setHeader("X-Accel-Buffering", "no");

        Set<String> known = this.topics.known();
        Set<String> wanted = Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(known::contains)
                .collect(Collectors.toUnmodifiableSet());

        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, wanted.isEmpty() ? this.topics.defaultTopics() : wanted);
        hub.register(clusterId, subscriber);

        emitter.onCompletion(() -> hub.remove(clusterId, subscriber));
        emitter.onTimeout(() -> hub.remove(clusterId, subscriber));
        emitter.onError(e -> hub.remove(clusterId, subscriber));

        if (lastEventId != null) {
            this.topics.replays().forEach((topic, replay) -> {
                if (subscriber.wants(topic)) {
                    for (EventReplay.Replayed missed : replay.since(clusterId, lastEventId, REPLAY_CAP)) {
                        hub.sendTo(subscriber, topic, missed.data(), missed.id());
                    }
                }
            });
        }
        return emitter;
    }
}
