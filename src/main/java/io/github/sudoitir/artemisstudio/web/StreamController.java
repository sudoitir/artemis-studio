package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.BrokerEventService;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.Subscriber;
import io.github.sudoitir.artemisstudio.web.dto.EventViews.BrokerEventView;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
 * <p>Signal topics carry a {@code {topic,clusterId,ts}} envelope; the
 * {@code events} topic carries the broker-event payload with an {@code id:} line
 * ({@code broker_event.seq}). A reconnecting client that presents
 * {@code Last-Event-ID} gets a bounded replay of the events it missed before
 * live delivery resumes.
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private static final Set<String> KNOWN_TOPICS =
            Set.of("topology", "health", "queues", "events", "consumers", "sessions", "connections", "rr");
    private static final Set<String> DEFAULT_TOPIC_SET = Set.of("topology", "health", "queues");
    private static final String DEFAULT_TOPICS = "topology,health,queues";
    private static final int REPLAY_CAP = 500;

    private final SseHub hub;
    private final BrokerEventService events;

    @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam UUID clusterId,
            @RequestParam(defaultValue = DEFAULT_TOPICS) String topics,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");

        Set<String> wanted = Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(KNOWN_TOPICS::contains)
                .collect(Collectors.toUnmodifiableSet());

        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, wanted.isEmpty() ? DEFAULT_TOPIC_SET : wanted);
        hub.register(clusterId, subscriber);

        emitter.onCompletion(() -> hub.remove(clusterId, subscriber));
        emitter.onTimeout(() -> hub.remove(clusterId, subscriber));
        emitter.onError(e -> hub.remove(clusterId, subscriber));

        if (lastEventId != null && subscriber.wants("events")) {
            for (BrokerEventView missed : events.since(clusterId, lastEventId, REPLAY_CAP)) {
                hub.sendTo(subscriber, "events", missed, Long.toString(missed.seq()));
            }
        }
        return emitter;
    }
}
