package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * The SSE fan-out (ADR-0018). One {@code Set<Subscriber>} per cluster;
 * {@link #publish} sends a tiny change-signal event to every subscriber that
 * asked for the topic. Events carry no data — the client refetches the matching
 * query key — so a broker that changes nothing produces only the heartbeat.
 *
 * <p>{@link #publish} does not deliver directly: it broadcasts a {@link BusFrame} to every replica
 * (ADR-0152), this one included, and each delivers it to its own subscribers when it arrives, so a
 * plugin publishing on one replica reaches the clients of all of them. The registry itself is
 * per-instance and does not survive a restart. The bus carries at most about 7 KB a frame: a larger
 * payload arrives as the plain {@code {topic,clusterId,ts}} signal, and the client refetches.
 *
 * <p>Delivery never blocks the caller, which is the bus dispatch thread: a frame is queued on each
 * subscriber, whose own virtual thread writes it ({@link Subscriber}). A client that stops reading
 * loses its queued frames to one {@link #RESYNC}; it does not delay the others.
 */
@Component
@PluginApi
@Slf4j
public class SseHub {

    /** The keep-alive event name. Not a topic: it is sent to every subscriber. */
    public static final String PING = "ping";

    /** Sent to every subscriber when the bus came back after a loss: frames in the gap are gone, so refetch. */
    public static final String RESYNC = "resync";

    /** Sent to every subscriber before its stream is closed: reconnect at once, presenting the last event id. */
    public static final String RECONNECT = "reconnect";

    private final Map<UUID, Set<Subscriber>> byCluster = new ConcurrentHashMap<>();
    /** Topics whose last publish failed, so an outage is logged once per topic and not once per frame. */
    private final Set<String> unpublished = ConcurrentHashMap.newKeySet();

    private final StudioBus bus;
    private final ObjectMapper mapper;

    public SseHub(StudioBus bus, ObjectMapper mapper) {
        this.bus = bus;
        this.mapper = mapper;
    }

    public void register(UUID clusterId, Subscriber subscriber) {
        byCluster.computeIfAbsent(clusterId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        subscriber.startDrain(
                frame -> sendTo(clusterId, subscriber, frame.topic(), frame.data(), frame.id()),
                () -> complete(subscriber));
    }

    /** Forget a subscriber whose stream is over, and stop its writer. */
    public void remove(UUID clusterId, Subscriber subscriber) {
        unregister(clusterId, subscriber);
        subscriber.stopDrain();
    }

    private void unregister(UUID clusterId, Subscriber subscriber) {
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
     * powers {@code Last-Event-ID} replay (ADR-0027). Sent over the bus: it reaches the subscribers of
     * every replica, and of this one only when it arrives back.
     *
     * <p>A signal is best effort: when the database cannot take it, because it or its pool is unavailable,
     * the failure is logged, once per topic until a publish succeeds again, and not thrown at the caller,
     * whose own work is not undone by a missed nudge. Clients refetch on the next reconnect or resync.
     * Nothing protects a caller's transaction specially: {@code pg_notify} with a capped payload cannot fail
     * as a statement, so inside a transaction the only failure is a lost connection, which ends that
     * transaction whatever is done here, and its commit reports it.
     */
    public void publish(UUID clusterId, String topic, Object data, String eventId) {
        BusFrame frame = new BusFrame(clusterId, topic, data == null ? null : mapper.valueToTree(data), eventId);
        try {
            bus.publish(frame);
            if (!unpublished.isEmpty()) {
                unpublished.clear();
            }
        } catch (DataAccessException e) {
            if (unpublished.add(topic)) {
                log.warn(
                        "Could not publish a '{}' frame for cluster {}: {}. Clients refetch when they reconnect;"
                                + " further failures of this topic are not logged until one succeeds",
                        topic,
                        clusterId,
                        e.getMessage());
            }
        }
    }

    /** A frame arrived, from this replica or another: deliver it to the local subscribers. */
    @EventListener
    void onFrame(BusFrame frame) {
        deliver(frame.clusterId(), frame.topic(), frame.data(), frame.id());
    }

    /** The bus is back: what was sent while it was down is lost, so every client refetches. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
        toAll(RESYNC);
    }

    private void deliver(UUID clusterId, String topic, Object data, String eventId) {
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
                synchronized (s) {
                    Subscriber.Held frame = new Subscriber.Held(topic, payload, eventId);
                    if (!s.hold(frame)) {
                        queue(s, frame);
                    }
                }
            }
        }
    }

    /**
     * End buffering: send what arrived while the replay ran, in order, and go live. A frame of a topic
     * in {@code replayed} whose id is not above the id already replayed for it is a repeat and is skipped.
     */
    public void release(UUID clusterId, Subscriber subscriber, Map<String, Long> replayed) {
        synchronized (subscriber) {
            for (Subscriber.Held frame : subscriber.unbuffer()) {
                Long upTo = replayed.get(frame.topic());
                if (upTo != null && frame.id() != null && Long.parseLong(frame.id()) <= upTo) {
                    continue;
                }
                queue(subscriber, frame);
            }
        }
    }

    /** Hand a frame to the subscriber's writer; logs, once per subscriber, when it is so far behind that it resyncs. */
    private void queue(Subscriber s, Subscriber.Held frame) {
        if (s.enqueue(frame)) {
            log.warn(
                    "An SSE client is {} frames behind and stopped reading; its queue was dropped and it is told to resync",
                    Subscriber.OUTBOUND_CAPACITY);
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
        toAll(PING);
    }

    /** One named event, carrying the server's clock, to every subscriber, whatever it asked for. */
    private void toAll(String event) {
        byCluster
                .values()
                .forEach(set -> set.forEach(s -> {
                    synchronized (s) {
                        queue(s, new Subscriber.Held(event, Instant.now().toEpochMilli(), null));
                    }
                }));
    }

    /** Writes one queued frame; a stream that cannot be written is dropped, and false stops its writer. */
    private boolean sendTo(UUID clusterId, Subscriber s, String event, Object data, String eventId) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) {
                builder.id(eventId);
            }
            s.emitter().send(builder);
            return true;
        } catch (IOException | RuntimeException e) {
            remove(clusterId, s);
            try {
                s.emitter().completeWithError(e);
            } catch (RuntimeException _) {
                // already closed
            }
            return false;
        }
    }

    private void complete(Subscriber s) {
        try {
            s.emitter().complete();
        } catch (RuntimeException _) {
            // already closed
        }
    }

    /** A session ended on any replica: complete the streams it opened, so a signed-out user gets no more events. */
    @EventListener(condition = "#signal.kind() == 'session-ended'")
    void onSessionEnded(ReplicaSignal signal) {
        complete(s -> signal.key().equals(s.sessionId()));
    }

    /** An API token was revoked on any replica: complete the streams it opened. */
    @EventListener(condition = "#signal.kind() == 'token-revoked'")
    void onTokenRevoked(ReplicaSignal signal) {
        UUID tokenId = UUID.fromString(signal.key());
        complete(s -> tokenId.equals(s.tokenId()));
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
     * {@link #onSessionEnded} cannot see: a session that timed out, or a signal lost while the bus was down.
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
            unregister(clusterId, s);
            s.discard();
        }));
    }

    /**
     * Tell every client to reconnect, then end every open stream and forget its subscribers. A client
     * told to reconnect does so at once, without backoff, presenting its last event id.
     */
    public void closeAll() {
        toAll(RECONNECT);
        byCluster.values().forEach(set -> set.forEach(Subscriber::finish));
        byCluster.clear();
    }

    /** Open streams across every cluster. */
    public int clientCount() {
        return byCluster.values().stream().mapToInt(Set::size).sum();
    }

    int subscriberCount(UUID clusterId) {
        Set<Subscriber> set = byCluster.get(clusterId);
        return set == null ? 0 : set.size();
    }
}
