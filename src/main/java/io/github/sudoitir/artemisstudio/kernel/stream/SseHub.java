package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.security.SessionEnded;
import io.github.sudoitir.artemisstudio.kernel.security.SessionIdChanged;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
@PluginApi
@Slf4j
public class SseHub {

    /** The keep-alive event name. Not a topic: it is sent to every subscriber. */
    public static final String PING = "ping";

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
        publish(clusterId, topic, null, null);
    }

    /**
     * Fan out {@code topic} to every subscriber of this cluster. Signal topics
     * pass {@code data == null} and get the {@code {topic,clusterId,ts}} envelope;
     * the {@code events} topic passes the real payload and an {@code eventId}
     * (the {@code broker_event.seq}), which becomes the SSE {@code id:} line and
     * powers {@code Last-Event-ID} replay (ADR-0027).
     */
    public void publish(UUID clusterId, String topic, Object data, String eventId) {
        Set<Subscriber> set = byCluster.get(clusterId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Object payload = data != null
                ? data
                : Map.of(
                        "topic",
                        topic,
                        "clusterId",
                        clusterId.toString(),
                        "ts",
                        Instant.now().toEpochMilli());
        for (Subscriber s : set) {
            if (s.wants(topic)) {
                sendTo(clusterId, s, topic, payload, eventId);
            }
        }
    }

    /**
     * Tell a subscriber that has just connected that its stream is open. Nothing else is sent until
     * the first event or heartbeat, up to {@code sse.heartbeat-interval} away, and the browser fires
     * {@code onopen} only once the first bytes arrive, so the console would sit "connecting" until then.
     */
    public void greet(Subscriber subscriber) {
        sendTo(subscriber, PING, Instant.now().toEpochMilli(), null);
    }

    /** Send one event to one subscriber — used for {@code Last-Event-ID} replay on connect. */
    public void sendTo(Subscriber subscriber, String topic, Object data, String eventId) {
        // clusterId is only needed to deregister a dead emitter; on the replay path the
        // controller owns registration, so a failure here just aborts the replay.
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(topic).data(data);
            if (eventId != null) {
                event.id(eventId);
            }
            subscriber.emitter().send(event);
        } catch (IOException | RuntimeException e) {
            subscriber.emitter().completeWithError(e);
        }
    }

    /**
     * Keep idle streams open through proxies, as a named event rather than a comment.
     *
     * <p>A comment keeps the socket warm and fires nothing an {@code EventSource} can
     * observe, so an intermediary that drops the connection without a clean close
     * leaves the client sitting on a dead socket believing it is live. A named event
     * gives the client a frame to miss, which is what its silence watchdog needs
     * (ADR-0052). It is additive: a client that does not subscribe to {@code ping}
     * ignores it.
     *
     * <p>Scheduled by {@code JobScheduler} on {@code sse.heartbeat-interval},
     * because the value that keeps a stream alive is a property of whatever proxy
     * sits in front. The watchdog window on the client is set well above it.
     */
    public void heartbeat() {
        byCluster.forEach((clusterId, set) -> set.forEach(s -> {
            try {
                s.emitter()
                        .send(SseEmitter.event().name(PING).data(Instant.now().toEpochMilli()));
            } catch (IOException | RuntimeException e) {
                drop(clusterId, s, e);
            }
        }));
    }

    private void sendTo(UUID clusterId, Subscriber s, String event, Object data, String eventId) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) {
                builder.id(eventId);
            }
            s.emitter().send(builder);
        } catch (IOException | RuntimeException e) {
            drop(clusterId, s, e);
        }
    }

    private void drop(UUID clusterId, Subscriber s, Exception cause) {
        remove(clusterId, s);
        try {
            s.emitter().completeWithError(cause);
        } catch (RuntimeException _) {
            // already closed
        }
    }

    /** A session ended on this instance: complete the streams it opened, so a signed-out user gets no more events. */
    @EventListener
    void onSessionEnded(SessionEnded ended) {
        complete(s -> ended.sessionId().equals(s.sessionId()));
    }

    /** A session was given a new id: its streams follow it, so they are not mistaken for those of a session that ended. */
    @EventListener
    void onSessionIdChanged(SessionIdChanged changed) {
        byCluster
                .values()
                .forEach(set -> set.stream()
                        .filter(s -> changed.oldId().equals(s.sessionId()))
                        .forEach(s -> s.followSession(changed.newId())));
    }

    /**
     * Complete the streams whose session {@code isLive} no longer accepts, which finds what
     * {@link #onSessionEnded} cannot see: a session that timed out, or ended on another instance.
     * Asks once per session however many streams it holds.
     */
    public void closeEndedSessions(Predicate<String> isLive) {
        Map<String, Boolean> live = new HashMap<>();
        complete(s -> s.sessionId() != null && !live.computeIfAbsent(s.sessionId(), isLive::test));
    }

    /**
     * Complete the streams opened with an API token that {@code isLive} no longer accepts: revoked,
     * expired, or its owner disabled or now required to hold a second factor it was minted without.
     * Asks once per token however many streams it holds.
     */
    public void closeEndedTokens(Predicate<UUID> isLive) {
        Map<UUID, Boolean> live = new HashMap<>();
        complete(s -> s.tokenId() != null && !live.computeIfAbsent(s.tokenId(), isLive::test));
    }

    private void complete(Predicate<Subscriber> ended) {
        byCluster.forEach((clusterId, set) -> set.stream().filter(ended).forEach(s -> {
            remove(clusterId, s);
            try {
                s.emitter().complete();
            } catch (RuntimeException _) {
                // already closed
            }
        }));
    }

    /** End every open stream and forget its subscribers. Clients reconnect to the next instance. */
    public void closeAll() {
        byCluster.forEach((clusterId, set) -> set.forEach(s -> {
            try {
                s.emitter().complete();
            } catch (RuntimeException _) {
                // already closed
            }
        }));
        byCluster.clear();
    }

    int subscriberCount(UUID clusterId) {
        Set<Subscriber> set = byCluster.get(clusterId);
        return set == null ? 0 : set.size();
    }
}
