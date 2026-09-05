package io.github.sudoitir.artemisstudio.domain.alerting;

import io.github.sudoitir.artemisstudio.persist.AlertStateEntity;
import java.time.Duration;
import java.time.Instant;

/**
 * The OK → PENDING → FIRING → OK debounce (design.md decision 4). Pure: no
 * Spring, no I/O — mutates the given {@link AlertStateEntity} in place and
 * returns a {@link Transition} only when one that matters to an operator
 * occurred (a fire or a resolve), never for PENDING bookkeeping. {@code since}
 * lives on the entity, so this survives a restart with no in-memory state.
 */
public final class AlertStateMachine {

    private AlertStateMachine() {}

    public enum TransitionKind {
        FIRED,
        RESOLVED
    }

    public record Transition(String subjectKey, TransitionKind kind, Double value) {}

    /** Advances {@code state} for one evaluation tick. {@code value} is null when {@code active} is false. */
    public static Transition advance(
            AlertStateEntity state, boolean active, Double value, int forSeconds, Instant now) {
        return switch (state.getState()) {
            case "OK" -> fromOk(state, active, value, forSeconds, now);
            case "PENDING" -> fromPending(state, active, value, forSeconds, now);
            case "FIRING" -> fromFiring(state, active, value, now);
            default -> throw new IllegalStateException("unknown alert_state: " + state.getState());
        };
    }

    private static Transition fromOk(
            AlertStateEntity state, boolean active, Double value, int forSeconds, Instant now) {
        if (!active) {
            return null;
        }
        state.setState("PENDING");
        state.setSince(now);
        state.setLastValue(value);
        if (forSeconds <= 0) {
            state.setState("FIRING");
            return new Transition(state.getSubjectKey(), TransitionKind.FIRED, value);
        }
        return null;
    }

    private static Transition fromPending(
            AlertStateEntity state, boolean active, Double value, int forSeconds, Instant now) {
        if (!active) {
            state.setState("OK");
            state.setSince(null);
            return null;
        }
        state.setLastValue(value);
        Instant since = state.getSince() != null ? state.getSince() : now;
        state.setSince(since);
        if (Duration.between(since, now).getSeconds() >= forSeconds) {
            state.setState("FIRING");
            return new Transition(state.getSubjectKey(), TransitionKind.FIRED, value);
        }
        return null;
    }

    private static Transition fromFiring(AlertStateEntity state, boolean active, Double value, Instant now) {
        if (active) {
            state.setLastValue(value);
            return null;
        }
        Double lastValue = state.getLastValue();
        state.setState("OK");
        state.setSince(null);
        return new Transition(state.getSubjectKey(), TransitionKind.RESOLVED, lastValue);
    }
}
