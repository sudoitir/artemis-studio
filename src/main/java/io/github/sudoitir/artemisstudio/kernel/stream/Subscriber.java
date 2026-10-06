package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One open SSE connection: its {@link SseEmitter}, the topics it asked for, the id of the session
 * that opened it, which is null for a caller with no session, and the id of the API token that
 * authenticated it, which is null for a session, and the principal it was opened by, whose access decides
 * every frame it is sent ({@link StreamAccess}). Identity is the emitter — the same client
 * reconnecting is a new subscriber. The stream ends with its session ({@link SseHub#onSessionEnded},
 * {@link SseHub#closeEndedSessions}) or when its token stops being accepted
 * ({@link SseHub#closeEndedTokens}). A session that is given a new id keeps its streams, which
 * follow it ({@link SseHub#onSessionIdChanged}).
 *
 * <p>Live frames do not go to the emitter from the thread that publishes them: each subscriber has a
 * bounded outbound queue that its own virtual thread drains, so one client that stops reading delays
 * nobody else. When the queue is full the queued frames are dropped and one {@code resync} takes
 * their place ({@link #enqueue}), which makes the client refetch what it missed.
 */
public final class Subscriber {

    private final SseEmitter emitter;
    private final Set<String> topics;
    private final UUID tokenId;
    private final StudioPrincipal principal;
    private final BlockingQueue<Held> outbound = new LinkedBlockingQueue<>(OUTBOUND_CAPACITY);
    private volatile String sessionId;
    private final AtomicReference<Thread> drainer = new AtomicReference<>();
    private final Object lock = new Object();
    private volatile boolean finished;
    private volatile boolean overflowLogged;

    /** Frames held back while a replay runs, or null once live. Guarded by {@link #lock}. */
    private List<Held> held;

    /** Frames a subscriber may have waiting to be written before it is considered stalled. */
    static final int OUTBOUND_CAPACITY = 1_000;

    /**
     * A frame that arrived while the subscriber was buffering, or that waits to be written. A null
     * {@code data} is a signal, written as the {@code {topic,clusterId,ts}} envelope; {@code about} is what
     * the frame concerns, null for the cluster as a whole.
     */
    public record Held(String topic, Object data, String id, BusFrame.About about) {

        Held(String topic, Object data, String id) {
            this(topic, data, id, null);
        }
    }

    /** Marks the end of the outbound queue: the drainer completes the emitter when it reaches it. */
    private static final Held COMPLETE = new Held(null, null, null);

    public Subscriber(
            SseEmitter emitter, Set<String> topics, String sessionId, UUID tokenId, StudioPrincipal principal) {
        this.emitter = emitter;
        this.topics = topics;
        this.sessionId = sessionId;
        this.tokenId = tokenId;
        this.principal = principal;
    }

    /**
     * A subscriber for the session that made {@code request}, if it has one, and the API token that
     * authenticated it, if one did.
     */
    public static Subscriber of(SseEmitter emitter, Set<String> topics, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID tokenId = auth != null && auth.getPrincipal() instanceof TokenPrincipal p ? p.tokenId() : null;
        StudioPrincipal principal = auth != null && auth.getPrincipal() instanceof StudioPrincipal p ? p : null;
        return new Subscriber(emitter, topics, session == null ? null : session.getId(), tokenId, principal);
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public Set<String> topics() {
        return topics;
    }

    /** The id of the session that opened the stream, as it is now. */
    public String sessionId() {
        return sessionId;
    }

    public UUID tokenId() {
        return tokenId;
    }

    /** Who the stream was opened for; frames are shown to them as their access is when each is written. */
    public StudioPrincipal principal() {
        return principal;
    }

    void followSession(String newId) {
        this.sessionId = newId;
    }

    /**
     * Hold the frames this subscriber would receive until {@link SseHub#release}, so a replay can be
     * sent first and the live frames that arrived meanwhile follow it.
     */
    public void buffer() {
        synchronized (lock) {
            held = new ArrayList<>();
        }
    }

    /**
     * Keeps {@code frame} for {@link #release} while the subscriber is buffering, and queues it when it is
     * live. Returns true when the queue overflowed for the first time, for the caller to log.
     */
    boolean deliver(Held frame) {
        synchronized (lock) {
            if (held == null) {
                return enqueue(frame);
            }
            held.add(frame);
            return false;
        }
    }

    /** Queues {@code frame} at once, buffering or not, in order with {@link #deliver} and {@link #release}. */
    boolean deliverNow(Held frame) {
        synchronized (lock) {
            return enqueue(frame);
        }
    }

    /**
     * Goes live and queues what was held, oldest first. A frame of a topic in {@code replayed} whose id is
     * not above the id already replayed for it is a repeat and is skipped. Returns true when the queue
     * overflowed for the first time, for the caller to log.
     */
    boolean release(Map<String, Long> replayed) {
        synchronized (lock) {
            List<Held> frames = held == null ? List.of() : held;
            held = null;
            boolean overflowed = false;
            for (Held frame : frames) {
                Long upTo = replayed.get(frame.topic());
                if (upTo != null && frame.id() != null && Long.parseLong(frame.id()) <= upTo) {
                    continue;
                }
                overflowed |= enqueue(frame);
            }
            return overflowed;
        }
    }

    /**
     * Start the virtual thread that writes the outbound queue, with {@code writer} returning false once
     * the stream is dead. Called once, when the subscriber is registered.
     */
    void startDrain(Predicate<Held> writer, Runnable completer) {
        drainer.set(Thread.ofVirtual().name("sse-drain").start(() -> {
            try {
                while (true) {
                    Held frame = outbound.take();
                    if (frame == COMPLETE) {
                        completer.run();
                        return;
                    }
                    if (!writer.test(frame)) {
                        return;
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    /**
     * Queue {@code frame} for this subscriber and return at once. When the queue is full it is emptied
     * and a single {@code resync} replaces it, so a client that cannot keep up refetches instead of
     * holding the publisher back. Returns true when that happened, for the caller to log once.
     */
    boolean enqueue(Held frame) {
        if (outbound.offer(frame)) {
            return false;
        }
        Held marker = finished ? COMPLETE : new Held(SseHub.RESYNC, System.currentTimeMillis(), null);
        do {
            outbound.clear();
        } while (!outbound.offer(marker)); // refused only when a concurrent producer refilled the queue
        boolean first = !overflowLogged;
        overflowLogged = true;
        return first;
    }

    /** Send what is queued, then complete the emitter. */
    void finish() {
        finished = true;
        enqueue(COMPLETE);
    }

    /** Drop what is queued and complete the emitter. */
    void discard() {
        finished = true;
        outbound.clear();
        enqueue(COMPLETE);
    }

    /** Stop the drainer; used when the stream ended by itself. */
    void stopDrain() {
        Thread t = drainer.get();
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
