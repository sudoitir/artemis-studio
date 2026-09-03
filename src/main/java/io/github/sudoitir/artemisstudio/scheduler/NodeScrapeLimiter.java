package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A per-node rate ceiling on management calls (CLAUDE.md non-negotiable #1 —
 * "Studio must never be the reason a broker falls over").
 *
 * <p>One {@link Semaphore} per broker node, sized to the configured
 * calls-per-second. {@link #acquire(UUID)} is taken before every scheduler POST;
 * {@link #refill()} tops every bucket back up to the ceiling once a second. This
 * is a coarse token bucket: it bounds sustained rate and absorbs a one-second
 * burst, which is all the scrape loop can produce (a handful of batched POSTs per
 * node per tier).
 */
// ponytail: per-node Semaphore + 1s refill. Swap for Bucket4j only if precise
// sub-second burst shaping is ever needed — same call sites.
@Component
@Slf4j
public class NodeScrapeLimiter {

    private final Map<UUID, Semaphore> perNode = new ConcurrentHashMap<>();
    private volatile int permitsPerSecond;

    public NodeScrapeLimiter(ArtemisStudioProperties properties) {
        this.permitsPerSecond = Math.max(1, properties.rateLimit().managementCallsPerSecond());
    }

    /** Runtime override hook — {@code SettingsService} calls this when the ceiling changes. */
    public void setPermitsPerSecond(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    public int permitsPerSecond() {
        return permitsPerSecond;
    }

    /**
     * Block until a permit for this node is available, then take it. A permit is
     * returned by the next {@link #refill()}, not by the caller — the bucket
     * drains within a tick and is topped back up each second.
     */
    public void acquire(UUID nodeId) throws InterruptedException {
        Semaphore sem = perNode.computeIfAbsent(nodeId, k -> new Semaphore(permitsPerSecond, true));
        if (!sem.tryAcquire(1, 5, TimeUnit.SECONDS)) {
            throw new InterruptedException("Timed out waiting for a scrape permit for node " + nodeId);
        }
    }

    /** Top every node's bucket back up to the current ceiling. Driven by its own tick. */
    @Scheduled(fixedRate = 1000)
    public void refill() {
        int ceiling = permitsPerSecond;
        perNode.forEach((nodeId, sem) -> {
            int deficit = ceiling - sem.availablePermits();
            if (deficit > 0) {
                sem.release(deficit);
            } else if (deficit < 0) {
                // Ceiling was lowered at runtime; drain the surplus without blocking.
                sem.tryAcquire(-deficit);
            }
        });
    }

    /** Drop a node's bucket when its cluster is removed. */
    public void forget(UUID nodeId) {
        perNode.remove(nodeId);
    }
}
