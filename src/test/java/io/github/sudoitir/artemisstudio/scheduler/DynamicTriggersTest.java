package io.github.sudoitir.artemisstudio.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * The one property that matters about these triggers: the schedule is re-read on
 * every fire, so a settings change lands without a restart. If this ever regresses
 * to reading the value once, every cadence setting silently goes back to needing a
 * restart — and it would still look correct in the UI, which is the failure mode
 * ADR-0025 was written about.
 */
class DynamicTriggersTest {

    private static final Instant LAST_RUN = Instant.parse("2026-01-01T00:00:00Z");

    private static TriggerContext contextEndingAt(Instant completion) {
        return new TriggerContext() {
            @Override
            public Instant lastScheduledExecution() {
                return completion;
            }

            @Override
            public Instant lastActualExecution() {
                return completion;
            }

            @Override
            public Instant lastCompletion() {
                return completion;
            }
        };
    }

    @Test
    void aFixedDelayTriggerRereadsTheIntervalOnEveryFire() {
        AtomicReference<Duration> interval = new AtomicReference<>(Duration.ofSeconds(5));
        Trigger trigger = DynamicTriggers.fixedDelay(interval::get);

        assertThat(trigger.nextExecution(contextEndingAt(LAST_RUN))).isEqualTo(LAST_RUN.plusSeconds(5));

        interval.set(Duration.ofSeconds(30));

        assertThat(trigger.nextExecution(contextEndingAt(LAST_RUN))).isEqualTo(LAST_RUN.plusSeconds(30));
    }

    /** No previous completion (the very first fire) must still schedule, not throw. */
    @Test
    void aFixedDelayTriggerSchedulesTheFirstRunFromNow() {
        Trigger trigger = DynamicTriggers.fixedDelay(() -> Duration.ofSeconds(5));

        assertThat(trigger.nextExecution(contextEndingAt(null))).isAfter(Instant.now());
    }

    @Test
    void aCronTriggerRereadsTheExpressionOnEveryFire() {
        AtomicReference<String> cron = new AtomicReference<>("0 30 3 * * *");
        Trigger trigger = DynamicTriggers.cron(cron::get);

        Instant nightly = trigger.nextExecution(contextEndingAt(LAST_RUN));

        cron.set("0 45 4 * * *");
        Instant moved = trigger.nextExecution(contextEndingAt(LAST_RUN));

        assertThat(moved).isNotNull().isNotEqualTo(nightly);
        assertThat(Duration.between(nightly, moved)).isEqualTo(Duration.ofMinutes(75));
    }
}
