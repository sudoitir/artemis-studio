package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.flow.MemberDeltas.Reading;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberDeltasTest {

    private final MemberDeltas deltas = new MemberDeltas();
    private final Instant t0 = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    void aFirstSightingIsUnknownNotZero() {
        var out = deltas.apply(t0, List.of(new Reading("p1", 500, 0)));

        assertThat(out.get("p1").rate()).isNull();
    }

    @Test
    void theSecondSweepGivesTheRateOverTheElapsedTime() {
        deltas.apply(t0, List.of(new Reading("p1", 500, 0)));

        var out = deltas.apply(t0.plusSeconds(10), List.of(new Reading("p1", 800, 0)));

        assertThat(out.get("p1").rate()).isEqualTo(30.0);
    }

    @Test
    void aCounterThatWentBackwardsIsUnknownAndRestartsTheBaseline() {
        deltas.apply(t0, List.of(new Reading("p1", 800, 0)));

        var reset = deltas.apply(t0.plusSeconds(10), List.of(new Reading("p1", 20, 0)));
        var after = deltas.apply(t0.plusSeconds(20), List.of(new Reading("p1", 120, 0)));

        assertThat(reset.get("p1").rate()).isNull();
        assertThat(after.get("p1").rate()).isEqualTo(10.0);
    }

    @Test
    void aMemberThatLeftIsForgottenAndIsUnknownIfItReturns() {
        deltas.apply(t0, List.of(new Reading("p1", 100, 0)));
        deltas.apply(t0.plusSeconds(10), List.of());

        var back = deltas.apply(t0.plusSeconds(20), List.of(new Reading("p1", 300, 0)));

        assertThat(back.get("p1").rate()).isNull();
    }

    @Test
    void aConsumerIsStalledOnlyAfterTwoIdleSweepsWithMessagesOutstanding() {
        deltas.apply(t0, List.of(new Reading("c1", 50, 5)));
        var one = deltas.apply(t0.plusSeconds(10), List.of(new Reading("c1", 50, 5)));
        var two = deltas.apply(t0.plusSeconds(20), List.of(new Reading("c1", 50, 5)));
        var recovered = deltas.apply(t0.plusSeconds(30), List.of(new Reading("c1", 51, 4)));

        assertThat(one.get("c1").stalled()).isFalse();
        assertThat(two.get("c1").stalled()).isTrue();
        assertThat(two.get("c1").rate()).isZero();
        assertThat(recovered.get("c1").stalled()).isFalse();
    }

    @Test
    void anIdleConsumerWithNothingOutstandingIsNotStalled() {
        deltas.apply(t0, List.of(new Reading("c1", 50, 0)));
        deltas.apply(t0.plusSeconds(10), List.of(new Reading("c1", 50, 0)));

        var out = deltas.apply(t0.plusSeconds(20), List.of(new Reading("c1", 50, 0)));

        assertThat(out.get("c1").stalled()).isFalse();
    }
}
