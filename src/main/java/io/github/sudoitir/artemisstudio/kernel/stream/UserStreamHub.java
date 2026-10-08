package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.security.SessionIdChanged;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The stream of one user (ADR-0018 applied to a person, not a cluster): the inbox bell and the held
 * operations list update live from it. It reuses {@link Subscriber}, so each stream has its own
 * bounded queue and writer, and ends with its session or token like a cluster stream does.
 *
 * <p>A {@link UserSignals} signal arrives from the bus on every replica, this one included, and is
 * written as a named event with no payload but the clock; the client refetches. After {@link BusResumed}
 * every stream is told to {@link SseHub#RESYNC}, because the bus never carries the truth.
 */
@Component
@Slf4j
public class UserStreamHub {

    /** The most streams one user may hold open on a replica. */
    public static final int MAX_STREAMS_PER_USER = 5;

    /** Each user's streams in the order they opened, oldest first. */
    private final Map<UUID, Set<Subscriber>> byUser = new ConcurrentHashMap<>();

    /**
     * Adds {@code subscriber} to {@code userId}'s streams and starts its writer. A user who already holds {@link
     * #MAX_STREAMS_PER_USER} loses the oldest. A closed tab's stream is only noticed when a write to it fails, up to a
     * heartbeat later, so the oldest is most likely one nobody reads any more: refusing the new stream instead would
     * leave a user who reloads a few times without live updates until the heartbeat.
     */
    public void register(UUID userId, Subscriber subscriber) {
        Subscriber[] evicted = {null};
        byUser.compute(userId, (k, set) -> {
            Set<Subscriber> streams = set == null ? new CopyOnWriteArraySet<>() : set;
            if (streams.size() >= MAX_STREAMS_PER_USER) {
                evicted[0] = streams.iterator().next();
                streams.remove(evicted[0]);
            }
            streams.add(subscriber);
            return streams;
        });
        if (evicted[0] != null) {
            evicted[0].discard();
        }
        subscriber.startDrain(frame -> write(userId, subscriber, frame), () -> complete(subscriber));
    }

    /** Forget a subscriber whose stream is over, and stop its writer. */
    public void remove(UUID userId, Subscriber subscriber) {
        unregister(userId, subscriber);
        subscriber.stopDrain();
    }

    private void unregister(UUID userId, Subscriber subscriber) {
        byUser.computeIfPresent(userId, (k, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    /** Tell a subscriber that has just connected that its stream is open. */
    public void greet(Subscriber subscriber) {
        try {
            subscriber
                    .emitter()
                    .send(SseEmitter.event()
                            .name(SseHub.PING)
                            .data(Instant.now().toEpochMilli()));
        } catch (IOException | RuntimeException e) {
            subscriber.emitter().completeWithError(e);
        }
    }

    /** A signal arrived, from this replica or another: tell the user's streams on this one. */
    @EventListener(condition = "#signal.kind() == 'inbox' or #signal.kind() == 'held'")
    void onSignal(ReplicaSignal signal) {
        UUID userId;
        try {
            userId = UUID.fromString(signal.key());
        } catch (IllegalArgumentException e) {
            log.warn("Ignored a '{}' signal with the key '{}'", signal.kind(), signal.key());
            return;
        }
        Set<Subscriber> streams = byUser.get(userId);
        if (streams != null) {
            streams.forEach(s -> warnIfBehind(s.deliverNow(event(signal.kind()))));
        }
    }

    /** The bus is back: signals sent while it was down are lost, so every client refetches. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
        toAll(SseHub.RESYNC);
    }

    /** Keep idle streams open through proxies and give the client's watchdog a frame to miss. */
    public void heartbeat() {
        toAll(SseHub.PING);
    }

    private void toAll(String event) {
        byUser.values().forEach(set -> set.forEach(s -> warnIfBehind(s.deliverNow(event(event)))));
    }

    private static Subscriber.Held event(String name) {
        return new Subscriber.Held(name, Instant.now().toEpochMilli(), null);
    }

    private void warnIfBehind(boolean overflowed) {
        if (overflowed) {
            log.warn(
                    "A user stream is {} frames behind and stopped reading; its queue was dropped and it is told"
                            + " to resync",
                    Subscriber.OUTBOUND_CAPACITY);
        }
    }

    private boolean write(UUID userId, Subscriber s, Subscriber.Held frame) {
        try {
            s.emitter().send(SseEmitter.event().name(frame.topic()).data(frame.data()));
            return true;
        } catch (IOException | RuntimeException e) {
            remove(userId, s);
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

    /** A session ended on any replica: complete the streams it opened. */
    @EventListener(condition = "#signal.kind() == 'session-ended'")
    void onSessionEnded(ReplicaSignal signal) {
        discardWhere(s -> signal.key().equals(s.sessionId()));
    }

    /** An API token was revoked on any replica: complete the streams it opened. */
    @EventListener(condition = "#signal.kind() == 'token-revoked'")
    void onTokenRevoked(ReplicaSignal signal) {
        UUID tokenId = UUID.fromString(signal.key());
        discardWhere(s -> tokenId.equals(s.tokenId()));
    }

    /** A session was given a new id: its streams follow it. */
    @EventListener
    void onSessionIdChanged(SessionIdChanged changed) {
        byUser.values()
                .forEach(set -> set.stream()
                        .filter(s -> changed.oldId().equals(s.sessionId()))
                        .forEach(s -> s.followSession(changed.newId())));
    }

    /** Complete the streams whose session {@code isLive} no longer accepts (timed out, or a signal was lost). */
    public void closeEndedSessions(Predicate<String> isLive) {
        Map<String, Boolean> live = new HashMap<>();
        discardWhere(s -> s.sessionId() != null && !live.computeIfAbsent(s.sessionId(), isLive::test));
    }

    /** Complete the streams opened with an API token that {@code isLive} no longer accepts. */
    public void closeEndedTokens(Predicate<UUID> isLive) {
        Map<UUID, Boolean> live = new HashMap<>();
        discardWhere(s -> s.tokenId() != null && !live.computeIfAbsent(s.tokenId(), isLive::test));
    }

    private void discardWhere(Predicate<Subscriber> ended) {
        byUser.forEach((userId, set) -> set.stream().filter(ended).forEach(s -> {
            unregister(userId, s);
            s.discard();
        }));
    }

    /** Tell every client to reconnect, then end every open stream. */
    public void closeAll() {
        toAll(SseHub.RECONNECT);
        byUser.values().forEach(set -> set.forEach(Subscriber::finish));
        byUser.clear();
    }

    int streamCount(UUID userId) {
        Set<Subscriber> set = byUser.get(userId);
        return set == null ? 0 : set.size();
    }
}
