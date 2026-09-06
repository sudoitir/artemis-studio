package io.github.sudoitir.artemisstudio.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;

/**
 * Triggers whose schedule is re-read on every fire, so a settings change applies
 * without a restart (ADR-0025, ADR-0048).
 *
 * <p>The whole point is the {@link Supplier}: a {@code @Scheduled} interval —
 * literal, placeholder or SpEL alike — is resolved once at wiring time, so the
 * value an operator sees in Settings and the value the scheduler is actually
 * using can silently disagree until the next restart. Asking for the interval
 * while computing the next run makes that disagreement impossible.
 */
public final class DynamicTriggers {

    private DynamicTriggers() {}

    /**
     * Fixed-delay semantics, preserved exactly: the gap is measured from the end of
     * the previous run, not its start, so a slow task never overlaps itself.
     */
    public static Trigger fixedDelay(Supplier<Duration> interval) {
        return context -> {
            Instant last = context.lastCompletion() != null
                    ? context.lastCompletion()
                    : context.lastActualExecution() != null ? context.lastActualExecution() : Instant.now();
            return last.plus(interval.get());
        };
    }

    /**
     * A cron whose expression is re-parsed each fire. Parsing per fire rather than
     * caching is deliberate — these fire a few times a day at most, and caching
     * would need invalidation for the one thing this exists to support.
     *
     * <p>A malformed expression cannot reach here: {@code SettingsService} validates
     * on write and the packaged default is the fallback. If one somehow does, the
     * task stops rather than falling back to a schedule nobody chose — a reaper that
     * silently runs on the wrong cron is worse than one that visibly stops.
     */
    public static Trigger cron(Supplier<String> expression) {
        return context -> {
            Instant last = context.lastCompletion() != null ? context.lastCompletion() : Instant.now();
            ZoneId zone = ZoneId.systemDefault();
            var next = CronExpression.parse(expression.get())
                    .next(last.atZone(zone).toLocalDateTime());
            return next == null ? null : next.atZone(zone).toInstant();
        };
    }
}
