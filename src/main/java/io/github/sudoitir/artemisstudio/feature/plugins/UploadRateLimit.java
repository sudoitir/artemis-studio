package io.github.sudoitir.artemisstudio.feature.plugins;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most {@link #LIMIT} uploads per user per hour (design.md §7): each one is parsed and hashed,
 * and a jar can be 50 MB.
 *
 * <p>ponytail: in memory, so a restart forgets the window. Studio is single-instance (ADR-0037).
 */
@Component
class UploadRateLimit {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Map<UUID, Deque<Instant>> byUser = new ConcurrentHashMap<>();
    private final Clock clock;

    UploadRateLimit(Clock clock) {
        this.clock = clock;
    }

    /** Records an upload by {@code userId}, or answers false without recording when the window is full. */
    boolean tryAcquire(UUID userId) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        byUser.compute(userId, (id, times) -> {
            Deque<Instant> recent = times == null ? new ArrayDeque<>() : times;
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() < LIMIT) {
                recent.addLast(now);
                allowed[0] = true;
            }
            return recent;
        });
        return allowed[0];
    }
}
