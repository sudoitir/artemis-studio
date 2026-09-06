package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.service.ClockOffsetService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Notices when Studio's own wall clock steps (ADR-0053).
 *
 * <p>An NTP correction, a VM resume from suspend, or someone setting the clock by
 * hand moves wall time without moving elapsed time. {@code System.nanoTime()} is
 * monotonic and unaffected, so comparing how much each advanced between two ticks
 * detects the step directly — the one wrong-clock scenario that leaves no trace in
 * any broker's data.
 *
 * <p>It matters because every clock offset Studio holds was measured against the
 * clock that just changed. They are discarded rather than kept and slowly
 * unlearned, which would report skew on brokers that never moved.
 */
@Component
@Slf4j
public class MonotonicClockWatch {

    /** Interval between checks; short enough to catch a step before much is stamped with it. */
    public static final Duration INTERVAL = Duration.ofSeconds(10);

    /**
     * Wall and elapsed time never agree exactly — scheduling jitter, a slewing NTP
     * client, a garbage-collection pause. A whole second of divergence in ten is a
     * step, not drift.
     */
    private static final long STEP_THRESHOLD_MS = 1_000;

    private final Clock clock;
    private final ClockOffsetService offsets;

    private long lastNanos;
    private long lastMillis;

    public MonotonicClockWatch(Clock clock, ClockOffsetService offsets) {
        this.clock = clock;
        this.offsets = offsets;
    }

    public void check() {
        long nanos = System.nanoTime();
        long millis = clock.millis();
        if (lastNanos == 0) {
            lastNanos = nanos;
            lastMillis = millis;
            return;
        }
        long elapsedMs = (nanos - lastNanos) / 1_000_000;
        long wallMs = millis - lastMillis;
        lastNanos = nanos;
        lastMillis = millis;

        long divergence = wallMs - elapsedMs;
        if (Math.abs(divergence) <= STEP_THRESHOLD_MS) {
            return;
        }
        log.warn(
                "Studio's wall clock stepped by {}ms relative to elapsed time (now {}). "
                        + "Clock-offset estimates for every broker have been discarded and will rebuild.",
                divergence,
                Instant.ofEpochMilli(millis));
        offsets.onStudioClockStepped();
    }
}
