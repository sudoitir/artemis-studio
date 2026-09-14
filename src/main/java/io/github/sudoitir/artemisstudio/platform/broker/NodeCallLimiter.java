package io.github.sudoitir.artemisstudio.platform.broker;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * A per-node rate ceiling on management requests (CLAUDE.md non-negotiable #1 —
 * "Studio must never be the reason a broker falls over").
 *
 * <p>One {@link Semaphore} per broker node, sized to the configured requests-per-second and
 * keyed by the node's Jolokia URL. {@link #acquire} is called by the transports themselves
 * before <em>every</em> request they send (ADR-0076) — never by a feature — so a new caller
 * cannot forget it and a caller that grows a second request is charged for it.
 * {@link #refill()} tops every bucket back up to the ceiling once a second. This is a coarse
 * token bucket: it bounds sustained rate and absorbs a one-second burst.
 */
// ponytail: per-node Semaphore + 1s refill. Swap for Bucket4j only if precise
// sub-second burst shaping is ever needed — same call sites.
@Component
public class NodeCallLimiter {

    /** How long one permit may be waited for before the request is refused rather than queued without end. */
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private final Map<String, Semaphore> perNode = new ConcurrentHashMap<>();
    private final Map<String, Duration> lastWait = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private volatile int permitsPerSecond;
    private volatile boolean closed;

    public NodeCallLimiter(RateLimitProperties properties, MeterRegistry meters) {
        this.permitsPerSecond = Math.max(1, properties.managementCallsPerSecond());
        this.meters = meters;
    }

    /** Runtime override hook — {@code SettingsService} calls this when the ceiling changes. */
    public void setPermitsPerSecond(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    public int permitsPerSecond() {
        return permitsPerSecond;
    }

    /**
     * Wait for {@code permits} of this node's ceiling and take them, for one request. A permit
     * is returned by the next {@link #refill()}, not by the caller. Permits are taken one at a
     * time, so a request needing more than the ceiling spreads over seconds instead of waiting
     * forever for a burst that can never be available.
     *
     * @throws BrokerConnectionException when Studio is shutting down, when capacity does not
     *     come within the wait, or when the thread is interrupted (its interrupt status kept)
     */
    public void acquire(String node, int permits) {
        if (closed) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Studio is shutting down; no new broker call is started.");
        }
        Semaphore sem = perNode.computeIfAbsent(node, k -> new Semaphore(permitsPerSecond, true));
        long started = System.nanoTime();
        try {
            for (int i = 0; i < permits; i++) {
                if (!sem.tryAcquire(MAX_WAIT.toNanos(), TimeUnit.NANOSECONDS)) {
                    meters.counter("studio.broker.permit.timeouts", "node", node)
                            .increment();
                    throw new BrokerConnectionException(
                            BrokerConnectionException.Kind.UNREACHABLE,
                            "Timed out waiting for this node's management-call ceiling: Studio is already calling"
                                    + " it as fast as the configured rate allows.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Interrupted while waiting for this node's management-call ceiling.");
        } finally {
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            lastWait.put(node, waited);
            meters.timer("studio.broker.permit.wait", "node", node).record(waited);
        }
        meters.counter("studio.broker.requests", "node", node).increment();
    }

    /** Top every node's bucket back up to the current ceiling. Driven every second by {@code BrokerJobs}. */
    public void refill() {
        int ceiling = permitsPerSecond;
        perNode.forEach((node, sem) -> {
            int deficit = ceiling - sem.availablePermits();
            if (deficit > 0) {
                sem.release(deficit);
            } else if (deficit < 0) {
                // Ceiling was lowered at runtime; drain the surplus without blocking.
                sem.tryAcquire(-deficit);
            }
        });
    }

    /** Refuse every later call: Studio is shutting down (operational-health spec). */
    public void close() {
        closed = true;
    }

    /** Accept calls again after a stopped context has been started. */
    public void open() {
        closed = false;
    }

    /** How long the latest request to this node waited for a permit; zero before any. */
    public Duration lastWait(String node) {
        return lastWait.getOrDefault(node, Duration.ZERO);
    }
}
