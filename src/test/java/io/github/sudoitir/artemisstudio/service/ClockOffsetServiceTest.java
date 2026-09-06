package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.ClockOffsetRegistry;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The verdict, which is the part an operator reads. Three clocks can be measured
 * directly; Studio's own is inferred from every node agreeing that it is wrong.
 */
class ClockOffsetServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final long TEN_MINUTES = 600_000;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private final ClockOffsetRegistry registry = new ClockOffsetRegistry(clock);
    private final BrokerNodeRepository nodes = mock(BrokerNodeRepository.class);

    private ClockOffsetService service() {
        ArtemisStudioProperties properties = mock(ArtemisStudioProperties.class);
        when(properties.rr())
                .thenReturn(new ArtemisStudioProperties.Rr(
                        30_000,
                        java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofMinutes(15),
                        4096,
                        java.time.Duration.ofDays(7),
                        "0 20 3 * * *",
                        2_000));
        return new ClockOffsetService(nodes, registry, clock, properties);
    }

    /**
     * A node whose Jolokia URL is what the registry keys readings by.
     *
     * <p>The id is normally assigned on persist, and the verdict is keyed by it, so
     * an unsaved entity would be indistinguishable from every other unsaved one.
     * Reflection rather than a database: nothing here needs one.
     */
    private static BrokerNodeEntity node(String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(CLUSTER, name, "PRIMARY", null);
        n.applyManualUrl(url);
        try {
            java.lang.reflect.Field id = BrokerNodeEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(n, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return n;
    }

    private void reading(String url, long offsetMs) {
        long t0 = 1_000_000;
        // Round trip of 20ms, so the midpoint is t0 + 10 and the reading is clean.
        registry.record(url, (t0 + 10 + offsetMs) / 1_000, t0, t0 + 20);
    }

    @Test
    void everyNodeAgreeingIsNoAlarm() {
        BrokerNodeEntity a = node("broker-1", "http://broker-1/jolokia");
        BrokerNodeEntity b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.findAll()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", 0);
        reading("http://broker-2/jolokia", 0);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.IN_AGREEMENT);
    }

    @Test
    void oneNodeOutOfStepIsThatNodesProblem() {
        BrokerNodeEntity a = node("broker-1", "http://broker-1/jolokia");
        BrokerNodeEntity b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.findAll()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", 0);

        ClockOffsetService service = service();
        service.refresh();

        ClockOffsetService.Assessment assessment = service.assessmentFor(CLUSTER);
        assertThat(assessment.verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
        assertThat(assessment.skewed())
                .singleElement()
                .satisfies(s -> assertThat(s.nodeName()).isEqualTo("broker-1"));
    }

    @Test
    void everyNodeOutOfStepTheSameWayImplicatesStudioItself() {
        // Every broker in the estate being ten minutes fast, simultaneously, in the
        // same direction, has one plausible common cause and it is not the brokers.
        BrokerNodeEntity a = node("broker-1", "http://broker-1/jolokia");
        BrokerNodeEntity b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.findAll()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.STUDIO_SUSPECT);
    }

    @Test
    void oppositeDirectionsAreTwoBrokenBrokers() {
        BrokerNodeEntity a = node("broker-1", "http://broker-1/jolokia");
        BrokerNodeEntity b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.findAll()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", -TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
    }

    @Test
    void oneWitnessIsNotCorroboration() {
        // A single skewed node cannot distinguish "this broker is wrong" from "Studio
        // is wrong", and the cheaper, likelier answer is named rather than the alarming one.
        BrokerNodeEntity only = node("broker-1", "http://broker-1/jolokia");
        when(nodes.findAll()).thenReturn(List.of(only));
        reading("http://broker-1/jolokia", TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
    }

    @Test
    void nothingMeasuredIsUnknownRatherThanHealthy() {
        when(nodes.findAll()).thenReturn(List.of(node("broker-1", "http://broker-1/jolokia")));

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.UNKNOWN);
    }

    @Test
    void aSteppedStudioClockThrowsAwayEveryEstimate() {
        BrokerNodeEntity a = node("broker-1", "http://broker-1/jolokia");
        when(nodes.findAll()).thenReturn(List.of(a));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        ClockOffsetService service = service();
        service.refresh();
        assertThat(service.offsetFor(a.getId())).isPresent();

        service.onStudioClockStepped();

        assertThat(service.offsetFor(a.getId())).isEmpty();
        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.UNKNOWN);
    }
}
