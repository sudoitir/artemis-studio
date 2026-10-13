package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

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
 * <p>Live frames do not go to the emitter from the thread that publishes them: each subscriber has an
 * outbound queue that its own virtual thread drains, so one client that stops reading delays nobody else
 * (ADR-0197). The queue holds at most {@link #OUTBOUND_CAPACITY} frames and {@link #OUTBOUND_BYTES} bytes. A
 * queued change signal is replaced by a newer one for the same topic and resources. When the bound is
 * still exceeded the queued frames are dropped and one {@code resync} takes their place, which makes the
 * client refetch what it missed. A client that does that {@link #OVERFLOWS_BEFORE_DROP} times within
 * {@link #OVERFLOW_WINDOW}, or whose write is blocked for more than {@link #WRITE_BLOCKED_LIMIT}, is sent
 * {@code reconnect} and closed: it comes back with its last event id. The drain thread ends on every path.
 */
public final class Subscriber {

    /** Frames a subscriber may have waiting to be written, or held during a replay, before it is behind. */
    static final int OUTBOUND_CAPACITY = 1_000;

    /** The size, by {@link #weigh}, of the frames a subscriber may have waiting, or held during a replay. */
    static final long OUTBOUND_BYTES = 1L << 20;

    /** Overflows within {@link #OVERFLOW_WINDOW} after which the stream is closed with {@code reconnect}. */
    static final int OVERFLOWS_BEFORE_DROP = 3;

    static final Duration OVERFLOW_WINDOW = Duration.ofSeconds(60);

    /** How long one write may stay blocked before the stream is closed with {@code reconnect}. */
    static final Duration WRITE_BLOCKED_LIMIT = Duration.ofSeconds(30);

    private static final long NOT_WRITING = Long.MIN_VALUE;

    private final SseEmitter emitter;
    private final Set<String> topics;
    private final UUID tokenId;
    private final StudioPrincipal principal;
    private volatile String sessionId;
    private final AtomicReference<Thread> drainer = new AtomicReference<>();
    private final Object lock = new Object();
    private volatile boolean finished;
    private volatile boolean overflowLogged;

    /** Frames held back while a replay runs, or null once live. Guarded by {@link #lock}. */
    private List<Held> held;

    private long heldBytes;
    private boolean heldOverflowed;

    /** The outbound queue and everything that describes it, guarded by {@link #queueLock}. */
    private final ReentrantLock queueLock = new ReentrantLock();

    private final Condition nonEmpty = queueLock.newCondition();
    private final ArrayDeque<Slot> queue = new ArrayDeque<>();
    private final Map<SignalKey, Slot> signals = new HashMap<>();
    private final ArrayDeque<Long> overflows = new ArrayDeque<>();
    private long queuedBytes;
    private boolean closing;
    private boolean drainStopped;
    private Listener listener = Listener.NONE;

    /** When the write in progress began, by {@link #nanos}, or {@link #NOT_WRITING}. Set by the drainer. */
    private volatile long writeStartedAt = NOT_WRITING;

    /** The clock the bounds are measured by; a test replaces it. */
    LongSupplier nanos = System::nanoTime;

    /**
     * A frame that arrived while the subscriber was buffering, or that waits to be written. A null
     * {@code data} is a signal, written as the {@code {topic,clusterId,ts}} envelope; {@code about} is what
     * the frame concerns, null for the cluster as a whole. {@code weight} is its size for the queue's
     * byte bound.
     */
    public record Held(String topic, Object data, String id, BusFrame.About about, int weight) {

        public Held(String topic, Object data, String id, BusFrame.About about) {
            this(topic, data, id, about, weigh(topic, data, id, about));
        }

        Held(String topic, Object data, String id) {
            this(topic, data, id, null);
        }
    }

    /** What a queue reports to its hub: frames lost, and that the stream is being closed for lag. */
    record Listener(ObjIntConsumer<String> onDropped, Runnable onClosing) {

        static final Listener NONE = new Listener((reason, frames) -> {}, () -> {});
    }

    private static final class Slot {
        Held frame;
        final SignalKey key;

        Slot(Held frame, SignalKey key) {
            this.frame = frame;
            this.key = key;
        }
    }

    /** A queued signal is replaced by a newer one with the same key. */
    private record SignalKey(String topic, BusFrame.About about) {}

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

    /** The size of a frame for the byte bound: its text, which is what is written, plus a fixed overhead. */
    private static int weigh(String topic, Object data, String id, BusFrame.About about) {
        long size = 64;
        size += topic == null ? 0 : topic.length();
        size += id == null ? 0 : id.length();
        if (data instanceof JsonNode node) {
            size += node.toString().length();
        } else if (data != null) {
            size += 32;
        }
        if (about != null) {
            size += about.queues().stream().mapToInt(String::length).sum();
            size += about.addresses().stream().mapToInt(String::length).sum();
        }
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    /**
     * Hold the frames this subscriber would receive until {@link SseHub#release}, so a replay can be
     * sent first and the live frames that arrived meanwhile follow it. The hold has the queue's bounds;
     * past them the held frames are dropped and {@link #release} queues a {@code resync} instead.
     */
    public void buffer() {
        synchronized (lock) {
            held = new ArrayList<>();
            heldBytes = 0;
            heldOverflowed = false;
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
            if (heldOverflowed) {
                listener.onDropped().accept("replay", 1);
            } else if (held.size() >= OUTBOUND_CAPACITY || heldBytes + frame.weight() > OUTBOUND_BYTES) {
                listener.onDropped().accept("replay", held.size() + 1);
                held.clear();
                heldBytes = 0;
                heldOverflowed = true;
            } else {
                held.add(frame);
                heldBytes += frame.weight();
            }
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
     * Goes live and queues what was held, oldest first, or a {@code resync} when the hold overflowed. A
     * frame of a topic in {@code replayed} whose id is not above the id already replayed for it is a repeat
     * and is skipped. Returns true when the queue overflowed for the first time, for the caller to log.
     */
    boolean release(Map<String, Long> replayed) {
        synchronized (lock) {
            List<Held> frames = held == null ? List.of() : held;
            boolean resync = heldOverflowed;
            held = null;
            heldOverflowed = false;
            boolean overflowed = false;
            if (resync) {
                overflowed = enqueue(new Held(SseHub.RESYNC, System.currentTimeMillis(), null));
            }
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
     * the stream is dead, and {@code completer} closing the emitter. The thread ends on every path, and
     * the emitter is completed whichever way it ends. Called once, when the subscriber is registered; a
     * subscriber whose drain was already stopped starts nothing.
     */
    void startDrain(Predicate<Held> writer, Runnable completer, Listener listener) {
        queueLock.lock();
        try {
            this.listener = listener;
            if (drainStopped) {
                return;
            }
            drainer.set(Thread.ofVirtual().name("sse-drain").start(() -> drain(writer, completer)));
        } finally {
            queueLock.unlock();
        }
    }

    private void drain(Predicate<Held> writer, Runnable completer) {
        try {
            while (true) {
                Held frame = take();
                if (frame == COMPLETE) {
                    return;
                }
                writeStartedAt = nanos.getAsLong();
                try {
                    if (!writer.test(frame)) {
                        return;
                    }
                } finally {
                    writeStartedAt = NOT_WRITING;
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            completer.run();
        }
    }

    private Held take() throws InterruptedException {
        queueLock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                nonEmpty.await();
            }
            Slot slot = queue.poll();
            if (slot.key != null) {
                signals.remove(slot.key);
            }
            queuedBytes -= slot.frame.weight();
            return slot.frame;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Queue {@code frame} for this subscriber and return at once. A signal replaces a queued one for the
     * same topic and resources. When the queue is full it is emptied and a single {@code resync} replaces
     * it, so a client that cannot keep up refetches instead of holding the publisher back; a client that
     * does so repeatedly, or whose write is blocked, is closed with {@code reconnect}. Returns true when the
     * queue first overflowed, for the caller to log once.
     */
    boolean enqueue(Held frame) {
        queueLock.lock();
        try {
            if (closing) {
                return false;
            }
            if (writeBlockedTooLong()) {
                listener.onDropped().accept("blocked", queue.size() + 1);
                close(true);
                return false;
            }
            SignalKey key =
                    frame.data() == null && frame.topic() != null ? new SignalKey(frame.topic(), frame.about()) : null;
            Slot queued = key == null ? null : signals.get(key);
            if (queued != null) {
                queuedBytes += frame.weight() - queued.frame.weight();
                queued.frame = frame;
                listener.onDropped().accept("coalesced", 1);
                return false;
            }
            if (queue.size() < OUTBOUND_CAPACITY && queuedBytes + frame.weight() <= OUTBOUND_BYTES) {
                add(new Slot(frame, key));
                return false;
            }
            return overflow();
        } finally {
            queueLock.unlock();
        }
    }

    /** Guarded by {@link #queueLock}. */
    private boolean overflow() {
        listener.onDropped().accept("overflow", queue.size() + 1);
        if (finished) {
            clear();
            add(new Slot(COMPLETE, null));
            return false;
        }
        long now = nanos.getAsLong();
        overflows.addLast(now);
        while (now - overflows.peekFirst() > OVERFLOW_WINDOW.toNanos()) {
            overflows.pollFirst();
        }
        if (overflows.size() >= OVERFLOWS_BEFORE_DROP) {
            close(false);
            return false;
        }
        clear();
        add(new Slot(new Held(SseHub.RESYNC, System.currentTimeMillis(), null), null));
        boolean first = !overflowLogged;
        overflowLogged = true;
        return first;
    }

    private boolean writeBlockedTooLong() {
        long started = writeStartedAt;
        return started != NOT_WRITING && nanos.getAsLong() - started > WRITE_BLOCKED_LIMIT.toNanos();
    }

    /**
     * Ends a stream that lags or is blocked: empties the queue, leaves {@code reconnect} and the end marker
     * for the writer to send, and tells the hub to forget the subscriber. A writer that is blocked in a
     * write is interrupted; one that is not is left to send what it was given. Guarded by {@link #queueLock}.
     */
    private void close(boolean writerBlocked) {
        closing = true;
        finished = true;
        clear();
        add(new Slot(new Held(SseHub.RECONNECT, System.currentTimeMillis(), null), null));
        add(new Slot(COMPLETE, null));
        listener.onClosing().run();
        Thread t = drainer.get();
        if (writerBlocked && t != null) {
            t.interrupt();
        }
    }

    /** Guarded by {@link #queueLock}. */
    private void add(Slot slot) {
        queue.add(slot);
        if (slot.key != null) {
            signals.put(slot.key, slot);
        }
        queuedBytes += slot.frame.weight();
        nonEmpty.signal();
    }

    /** Guarded by {@link #queueLock}. */
    private void clear() {
        queue.clear();
        signals.clear();
        queuedBytes = 0;
    }

    private void clearQueue() {
        queueLock.lock();
        try {
            clear();
        } finally {
            queueLock.unlock();
        }
    }

    /** Send what is queued, then complete the emitter. */
    void finish() {
        finished = true;
        enqueue(COMPLETE);
    }

    /** Drop what is queued and complete the emitter. */
    void discard() {
        finished = true;
        clearQueue();
        enqueue(COMPLETE);
    }

    /** Drop what is queued, tell the client it was replaced by a newer stream, and complete the emitter. */
    void evict() {
        finished = true;
        clearQueue();
        enqueue(new Held(SseHub.EVICTED, System.currentTimeMillis(), null));
        enqueue(COMPLETE);
    }

    /**
     * Stop the drainer, whether or not it has started: used when the stream ended by itself. A drain
     * started afterwards does not start at all.
     */
    void stopDrain() {
        queueLock.lock();
        try {
            drainStopped = true;
            Thread t = drainer.get();
            if (t != null) {
                t.interrupt();
            }
        } finally {
            queueLock.unlock();
        }
    }

    public boolean wants(String topic) {
        return topics.contains(topic);
    }
}
