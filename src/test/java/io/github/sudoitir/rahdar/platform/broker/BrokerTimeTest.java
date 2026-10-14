package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Putting a broker's timestamp onto Rahdar's timeline. */
class BrokerTimeTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final Instant BROKER_SAID = Instant.parse("2026-09-06T12:10:00Z");

    private static BrokerTime with(long offsetMs, long uncertaintyMs) {
        ClockOffset offset = new ClockOffset(offsetMs, uncertaintyMs, 20, 5, Instant.now());
        return new BrokerTime(id -> Optional.of(offset));
    }

    @Test
    void aBrokerRunningFastHasItsHeadStartRemoved() {
        Instant rahdar = with(600_000, 510).toRahdarTime(NODE, BROKER_SAID);
        assertThat(rahdar).isEqualTo(BROKER_SAID.minusSeconds(600));
    }

    @Test
    void aBrokerRunningSlowIsMovedForward() {
        Instant rahdar = with(-600_000, 510).toRahdarTime(NODE, BROKER_SAID);
        assertThat(rahdar).isEqualTo(BROKER_SAID.plusSeconds(600));
    }

    @Test
    void anOffsetInsideItsOwnErrorBarIsNotACorrection() {
        // Correcting by less than the measurement error is noise dressed as precision.
        assertThat(with(300, 510).toRahdarTime(NODE, BROKER_SAID)).isEqualTo(BROKER_SAID);
    }

    @Test
    void anUnmeasuredNodePassesThroughUnchanged() {
        // No measurement is no evidence of skew. Inventing a correction would be worse
        // than applying none.
        assertThat(BrokerTime.identity().toRahdarTime(NODE, BROKER_SAID)).isEqualTo(BROKER_SAID);
    }

    @Test
    void millisecondsAndInstantsAgree() {
        BrokerTime time = with(600_000, 510);
        assertThat(time.toRahdarTime(NODE, BROKER_SAID.toEpochMilli())).isEqualTo(time.toRahdarTime(NODE, BROKER_SAID));
    }
}
