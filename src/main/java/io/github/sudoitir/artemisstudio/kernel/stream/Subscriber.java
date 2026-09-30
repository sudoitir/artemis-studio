package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One open SSE connection: its {@link SseEmitter}, the topics it asked for, the id of the session
 * that opened it, which is null for a caller with no session, and the id of the API token that
 * authenticated it, which is null for a session. Identity is the emitter — the same client
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
    private final BlockingQueue<Held> outbound = new LinkedBlockingQueue<>(OUTBOUND_CAPACITY);
    private volatile String sessionId;
    private volatile Thread drainer;
    private volatile boolean finished;
    private volatile boolean overflowLogged;

    /** Frames held back while a replay runs, or null once live. Guarded by this subscriber's monitor. */
    private List<Held> held;

    /** Frames a subscriber may have waiting to be written before it is considered stalled. */
    static final int OUTBOUND_CAPACITY = 1_000;

    /** A frame that arrived while the subscriber was buffering, or that waits to be written. */
    record Held(String topic, Object data, String id) {}

    /** Marks the end of the outbound queue: the drainer completes the emitter when it reaches it. */
    private static final Held COMPLETE = new Held(null, null, null);

    public Subscriber(SseEmitter emitter, Set<String> topics, String sessionId, UUID tokenId) {
        this.emitter = emitter;
        this.topics = topics;
        this.sessionId = sessionId;
        this.tokenId = tokenId;
    }

    /** A subscriber that has a session and no token. */
    public Subscriber(SseEmitter emitter, Set<String> topics, String sessionId) {
        this(emitter, topics, sessionId, null);
    }

    /**
     * A subscriber for the session that made {@code request}, if it has one, and the API token that
     * authenticated it, if one did.
     */
    public static Subscriber of(SseEmitter emitter, Set<String> topics, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID tokenId = auth != null && auth.getPrincipal() instanceof TokenPrincipal p ? p.tokenId() : null;
        return new Subscriber(emitter, topics, session == null ? null : session.getId(), tokenId);
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

    void followSession(String newId) {
        this.sessionId = newId;
    }

    /**
     * Hold the frames this subscriber would receive until {@link SseHub#release}, so a replay can be
     * sent first and the live frames that arrived meanwhile follow it.
     */
    public synchronized void buffer() {
        held = new ArrayList<>();
    }

    /** Keeps {@code frame} for {@link SseHub#release} and says so, or returns false when the subscriber is live. */
    synchronized boolean hold(Held frame) {
        if (held == null) {
            return false;
        }
        held.add(frame);
        return true;
    }

    /** Goes live and returns what was held, oldest first. */
    synchronized List<Held> unbuffer() {
        List<Held> frames = held == null ? List.of() : held;
        held = null;
        return frames;
    }

    /**
     * Start the virtual thread that writes the outbound queue, with {@code writer} returning false once
     * the stream is dead. Called once, when the subscriber is registered.
     */
    void startDrain(Predicate<Held> writer, Runnable completer) {
        drainer = Thread.ofVirtual().name("sse-drain").start(() -> {
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
        });
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
        outbound.clear();
        outbound.offer(finished ? COMPLETE : new Held(SseHub.RESYNC, System.currentTimeMillis(), null));
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
        Thread t = drainer;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
