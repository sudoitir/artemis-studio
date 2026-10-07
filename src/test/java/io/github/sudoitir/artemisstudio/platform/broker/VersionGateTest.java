package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities.CapabilityStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionGateTest {

    private static final VersionGate GATE =
            new VersionGate("DIVERT_CREATE", "Creating a divert", new BrokerVersion(2, 38, 0));

    private static NodeEndpoint node(String name, String version) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                null,
                null,
                null,
                "PRIMARY",
                "LIVE",
                true,
                null,
                null,
                version,
                null,
                null,
                null,
                null,
                null,
                false,
                true);
    }

    @Test
    void availableWhenEveryNodeIsNewEnough() {
        VersionGate.Assessment a = GATE.assess(List.of(node("a", "2.38.0"), node("b", "2.57.0")));
        assertThat(a.status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(a.nodes()).allMatch(VersionGate.NodeVerdict::supported);
    }

    @Test
    void unavailableNamingTheRequiredVersionWhenNoNodeIs() {
        VersionGate.Assessment a = GATE.assess(List.of(node("a", "2.35.0")));
        assertThat(a.status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(a.requiredVersion()).isEqualTo("2.38.0");
        assertThat(a.reason())
                .contains("Creating a divert needs Artemis 2.38.0")
                .contains("2.35.0");
    }

    @Test
    void mixedVersionsOfferItOnlyOnTheNodesThatSupportIt() {
        VersionGate.Assessment a = GATE.assess(List.of(node("new", "2.57.0"), node("old", "2.35.0")));
        assertThat(a.status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(a.reason()).contains("old");
        assertThat(a.nodes())
                .extracting(VersionGate.NodeVerdict::nodeName, VersionGate.NodeVerdict::supported)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("new", true),
                        org.assertj.core.groups.Tuple.tuple("old", false));
    }

    @Test
    void unknownWhenNoNodeHasReportedAVersion() {
        assertThat(GATE.assess(List.of(node("a", null))).status()).isEqualTo(CapabilityStatus.UNKNOWN);
    }

    @Test
    void refusalOnlyForAKnownOlderVersion() {
        assertThat(GATE.refusal("2.35.0")).contains("2.38.0");
        assertThat(GATE.refusal("2.38.0")).isNull();
        assertThat(GATE.refusal(null)).isNull();
    }
}
