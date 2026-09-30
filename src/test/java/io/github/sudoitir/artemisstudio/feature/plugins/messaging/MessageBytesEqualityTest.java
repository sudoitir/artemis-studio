package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageBytesEqualityTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Test
    void messagesWithEqualBytesAreEqual() {
        OutboundMessage a =
                new OutboundMessage(CLUSTER, "orders", new byte[] {1, 2}, false, Map.of(), Map.of(), true, null);
        OutboundMessage b =
                new OutboundMessage(CLUSTER, "orders", new byte[] {1, 2}, false, Map.of(), Map.of(), true, null);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).hasToString(b.toString());
        assertThat(a.toString()).contains("body=2 bytes");

        PluginMessage x =
                new PluginMessage("k", CLUSTER, CLUSTER, "q", new byte[] {1, 2}, false, Map.of(), Map.of(), 1);
        PluginMessage y =
                new PluginMessage("k", CLUSTER, CLUSTER, "q", new byte[] {1, 2}, false, Map.of(), Map.of(), 1);
        PluginMessage other =
                new PluginMessage("k", CLUSTER, CLUSTER, "q", new byte[] {9}, false, Map.of(), Map.of(), 1);
        assertThat(x).isEqualTo(y).hasSameHashCodeAs(y).isNotEqualTo(other);
    }
}
