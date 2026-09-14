package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.artemisstudio.feature.flow.ClientEdges.Member;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientEdgesTest {

    @Test
    void consumersOfOneApplicationOnOneQueueBecomeOneEdgeWithACount() {
        var edges = ClientEdges.aggregate(List.of(
                consumer("billing", "10.0.0.5:50001", "orders", 10.0, 1, false),
                consumer("billing", "10.0.0.5:50002", "orders", 5.0, 2, false),
                consumer("billing", "10.0.0.5:50003", "orders", null, 0, true)));

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.memberCount()).isEqualTo(3);
            assertThat(e.remoteHost()).isEqualTo("10.0.0.5");
            assertThat(e.rate()).isEqualTo(15.0);
            assertThat(e.unacked()).isEqualTo(3);
            assertThat(e.stalled()).isTrue();
        });
    }

    @Test
    void aGroupWithNoKnownRateStaysUnknown() {
        var edges = ClientEdges.aggregate(List.of(
                consumer("billing", "10.0.0.5:1", "orders", null, 0, false),
                consumer("billing", "10.0.0.5:2", "orders", null, 0, false)));

        assertThat(edges.getFirst().rate()).isNull();
    }

    @Test
    void differentQueuesOrKindsAreDifferentEdges() {
        var edges = ClientEdges.aggregate(List.of(
                consumer("billing", "10.0.0.5:1", "orders", 1.0, 0, false),
                consumer("billing", "10.0.0.5:1", "refunds", 1.0, 0, false),
                new Member(Kind.PRODUCE, "billing", "u", "10.0.0.5:1", "CORE", "orders", null, 1.0, 0, false)));

        assertThat(edges).hasSize(3);
    }

    @Test
    void theHostDropsTheEphemeralPortInEveryBrokerSpelling() {
        assertThat(ClientEdges.host("10.0.0.5:51234")).isEqualTo("10.0.0.5");
        assertThat(ClientEdges.host("/10.0.0.5:51234")).isEqualTo("10.0.0.5");
        assertThat(ClientEdges.host("[::1]:51234")).isEqualTo("::1");
        assertThat(ClientEdges.host("fe80::1")).isEqualTo("fe80::1");
        assertThat(ClientEdges.host("invm:0")).isEqualTo("invm");
        assertThat(ClientEdges.host(null)).isEmpty();
    }

    private static Member consumer(
            String clientId, String remote, String queue, Double rate, long unacked, boolean stalled) {
        return new Member(Kind.CONSUME, clientId, "artemis", remote, "CORE", queue, queue, rate, unacked, stalled);
    }
}
