package io.github.sudoitir.artemisstudio.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.ClockOffsetRegistry.ClockOffset;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The offset estimator: min-RTT selection, smoothing, and what it refuses to claim. */
class ClockOffsetRegistryTest {

    private static final String URL = "http://broker-1:8161/console/jolokia";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private final ClockOffsetRegistry registry = new ClockOffsetRegistry(clock);

    @Test
    void aBrokerAgreeingWithStudioReportsNothingWorthReporting() {
        // t0=1000000, t1=1000200 → midpoint 1000100ms. The broker says 1000 seconds.
        registry.record(URL, 1_000, 1_000_000, 1_000_200);

        ClockOffset offset = registry.offsetFor(URL).orElseThrow();
        // The whole disagreement is Jolokia's own second-granularity rounding.
        assertThat(Math.abs(offset.offsetMs())).isLessThan(ClockOffsetRegistry.QUANTISATION_MS);
        assertThat(offset.isMeaningful()).isFalse();
    }

    @Test
    void aBrokerTenMinutesFastIsReportedAsSuch() {
        long tenMinutes = 600_000;
        registry.record(URL, (1_000_000 + tenMinutes) / 1_000, 1_000_000, 1_000_020);

        ClockOffset offset = registry.offsetFor(URL).orElseThrow();
        assertThat(offset.offsetMs()).isCloseTo(tenMinutes, org.assertj.core.data.Offset.offset(1_000L));
        assertThat(offset.isMeaningful()).isTrue();
    }

    @Test
    void uncertaintyCoversTheRoundTripAndTheSecondGranularity() {
        registry.record(URL, 1_000, 1_000_000, 1_000_400); // 400ms round trip

        ClockOffset offset = registry.offsetFor(URL).orElseThrow();
        assertThat(offset.uncertaintyMs()).isEqualTo(200 + ClockOffsetRegistry.QUANTISATION_MS);
    }

    @Test
    void aSlowReadingDoesNotDragTheEstimate() {
        // A fast, clean reading first: the broker agrees with Studio.
        registry.record(URL, 1_000, 1_000_000, 1_000_010);
        long clean = registry.offsetFor(URL).orElseThrow().offsetMs();

        // Then one that spent five seconds queued somewhere. Its apparent offset is
        // enormous, and it is exactly the reading NTP's min-RTT rule exists to drop.
        registry.record(URL, 1_010, 1_000_000, 1_005_000);

        assertThat(registry.offsetFor(URL).orElseThrow().offsetMs()).isEqualTo(clean);
    }

    @Test
    void aResponseWithNoTimestampTeachesNothing() {
        // Zero is what a stripped or absent field looks like once unboxed. It must not
        // be read as "the broker thinks it is 1970".
        registry.record(URL, 0, 1_000_000, 1_000_010);

        assertThat(registry.offsetFor(URL)).isEmpty();
    }

    @Test
    void anUnmeasuredNodeIsUnknownRatherThanInAgreement() {
        assertThat(registry.offsetFor("http://broker-2:8161/console/jolokia")).isEmpty();
    }

    @Test
    void aSteppedStudioClockDiscardsEverythingMeasuredAgainstIt() {
        registry.record(URL, 1_600, 1_000_000, 1_000_010);
        assertThat(registry.offsetFor(URL)).isPresent();

        registry.invalidate();

        assertThat(registry.offsetFor(URL)).isEmpty();
    }
}
