package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.Subscriber;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/stream?clusterId={uuid}&topics={csv}} — the single
 * multiplexed change-signal stream per cluster (ADR-0003, ADR-0018). No timeout;
 * {@link SseHub}'s heartbeat keeps it open. {@code X-Accel-Buffering: no} tells
 * proxies not to buffer it.
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private static final Set<String> KNOWN_TOPICS = Set.of("topology", "health", "queues");

    private final SseHub hub;

    @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam UUID clusterId,
            @RequestParam(defaultValue = "topology,health,queues") String topics,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");

        Set<String> wanted = Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(KNOWN_TOPICS::contains)
                .collect(Collectors.toUnmodifiableSet());

        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, wanted.isEmpty() ? KNOWN_TOPICS : wanted);
        hub.register(clusterId, subscriber);

        emitter.onCompletion(() -> hub.remove(clusterId, subscriber));
        emitter.onTimeout(() -> hub.remove(clusterId, subscriber));
        emitter.onError(e -> hub.remove(clusterId, subscriber));
        return emitter;
    }
}
