package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process fan-out from the capture consumers to everything that wants a captured
 * message (ADR-0062, generalising ADR-0060 D4 from two consumers to three): the
 * message index, an operator's live tail, and request-reply correlation.
 *
 * <p>It also holds the per-subscription ingest rate cap, and it holds it here rather
 * than in each sink for one reason: a firehose has to be stopped once, in front of
 * everything, or it starves the other subscriptions on the way to being dropped by
 * each sink separately. Over-rate messages are dropped and counted — never buffered,
 * because a buffer in front of an unbounded producer only moves the failure.
 */
@Component
@Slf4j
public class CaptureBus {

    /** One captured message, with the identity Studio resolved for it (D2). */
    public record Captured(
            UUID clusterId, UUID subscriptionId, Row row, String origAddress, Long sourceMessageId, Instant at) {}

    /** Anything that wants captured messages. Sinks are additive and independent. */
    public interface Listener {
        void captured(Captured captured);

        /**
         * Commit whatever is buffered. Called by a consumer before it acknowledges, so
         * that a batch is never acknowledged on the broker before the rows it produced
         * are durable — the crash window is redelivery, never loss.
         */
        default void flush() {}
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, RateGate> gates = new ConcurrentHashMap<>();

    public void register(Listener listener) {
        listeners.add(listener);
    }

    public void unregister(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Offer a captured message. Returns false when the subscription's rate cap
     * rejected it, which the caller counts as a drop against that node.
     */
    public boolean publish(Captured captured, int maxRatePerSecond) {
        RateGate gate = gates.computeIfAbsent(captured.subscriptionId(), id -> new RateGate());
        if (!gate.allow(maxRatePerSecond)) {
            return false;
        }
        for (Listener listener : listeners) {
            try {
                listener.captured(captured);
            } catch (RuntimeException e) {
                // One sink's failure is not the others'. The index must not stop
                // because a tail's client went away mid-write, and vice versa.
                log.debug("A capture sink rejected a message: {}", e.getMessage());
            }
        }
        return true;
    }

    /** Commit every sink's buffer. Called before a consumer acknowledges a batch. */
    public void flush() {
        for (Listener listener : listeners) {
            try {
                listener.flush();
            } catch (RuntimeException e) {
                log.warn("A capture sink could not commit its batch: {}", e.getMessage());
            }
        }
    }

    /** Forget a subscription's rate state. */
    public void forget(UUID subscriptionId) {
        gates.remove(subscriptionId);
    }

    /**
     * A one-second token bucket. Deliberately not a smoothing limiter: capture is
     * bursty by nature and the cap exists to stop a sustained firehose, not to
     * reshape a spike into latency.
     */
    private static final class RateGate {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis() / 1000L);
        private final AtomicLong count = new AtomicLong();

        boolean allow(int maxPerSecond) {
            long second = System.currentTimeMillis() / 1000L;
            if (windowStart.getAndSet(second) != second) {
                count.set(0);
            }
            return count.incrementAndGet() <= maxPerSecond;
        }
    }
}
