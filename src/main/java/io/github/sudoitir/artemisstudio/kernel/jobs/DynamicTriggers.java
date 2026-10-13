package io.github.sudoitir.artemisstudio.kernel.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
            Instant last = context.lastCompletion();
            if (last == null) {
                last = context.lastActualExecution();
            }
            if (last == null) {
                last = context.getClock().instant();
            }
            return last.plus(interval.get());
        };
    }

    /**
     * A cron whose expression is re-parsed each fire, and read in UTC whatever the server's time zone.
     * Parsing per fire rather than caching is deliberate — these fire a few times a day at most, and
     * caching would need invalidation for the one thing this exists to support.
     *
     * <p>A malformed expression throws {@link IllegalArgumentException} from the trigger. The runner
     * marks the job's schedule invalid and asks again a minute later, so a corrected setting runs
     * again without a restart, and a reaper that cannot read its cron is visibly degraded rather than
     * silently on a schedule nobody chose.
     */
    public static Trigger cron(Supplier<String> expression) {
        return context -> {
            Instant last = context.lastCompletion() != null
                    ? context.lastCompletion()
                    : context.getClock().instant();
            var next = CronExpression.parse(expression.get()).next(last.atZone(ZoneOffset.UTC));
            return next == null ? null : next.toInstant();
        };
    }
}
