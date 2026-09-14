package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.QueryResult.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process fan-out from the capture consumers to everything that wants a captured
 * message besides the index (ADR-0062): an operator's live tail and request-reply
 * correlation.
 *
 * <p>The index is deliberately not a listener. It is the store a drain acknowledges
 * against (ADR-0077), so the drain writes to it directly and acknowledges only what
 * committed. A listener is best-effort: its failure is logged and never reaches the store.
 *
 * <p>It also holds the per-subscription ingest rate cap, shared by every drain of that
 * subscription. Over the cap a drain waits rather than drops (ADR-0077): the message stays in
 * the bounded capture queue, so a firehose is slowed without Studio buffering it, and the only
 * way the cap can turn into loss is the queue's own, counted, bound.
 */
@Component
@Slf4j
public class CaptureBus {

    /** One captured message, with the identity Studio resolved for it (D2). */
    public record Captured(
            UUID clusterId, UUID subscriptionId, Row row, String origAddress, Long sourceMessageId, Instant at) {}

    /** Anything besides the index that wants captured messages. Best-effort, additive and independent. */
    public interface Listener {
        void captured(Captured captured);
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
     * Take a permit from the subscription's rate cap. Returns 0 when this message is admitted,
     * or how many milliseconds to wait before asking again.
     */
    public long admit(UUID subscriptionId, int maxRatePerSecond) {
        return gates.computeIfAbsent(subscriptionId, id -> new RateGate()).admit(maxRatePerSecond);
    }

    /** Hand an admitted message to the listeners. */
    public void publish(Captured captured) {
        for (Listener listener : listeners) {
            try {
                listener.captured(captured);
            } catch (RuntimeException e) {
                // One listener's failure is not the others', and never the store's.
                log.debug("A capture listener rejected a message: {}", e.getMessage());
            }
        }
    }

    /** Forget a subscription's rate state. */
    public void forget(UUID subscriptionId) {
        gates.remove(subscriptionId);
    }

    /**
     * A one-second window. Deliberately not a smoothing limiter: capture is bursty by nature
     * and the cap exists to hold a sustained firehose to a rate, not to reshape a spike.
     */
    private static final class RateGate {
        private long second = -1;
        private long count;

        synchronized long admit(int maxPerSecond) {
            long now = System.currentTimeMillis();
            if (now / 1000L != second) {
                second = now / 1000L;
                count = 0;
            }
            if (count < maxPerSecond) {
                count++;
                return 0;
            }
            return 1000L - now % 1000L;
        }
    }
}
