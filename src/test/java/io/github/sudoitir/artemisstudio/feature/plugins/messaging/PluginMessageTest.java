package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PluginMessageTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private static PluginMessage message(byte[] body) {
        return new PluginMessage("k", CLUSTER, NODE, "q", body, true, Map.of("h", 1), Map.of("p", "v"), 1);
    }

    @Test
    void bodyTextDecodesUtf8() {
        assertThat(message("héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .bodyText())
                .isEqualTo("héllo");
    }

    @Test
    void equalityComparesEveryComponent() {
        PluginMessage base = message(new byte[] {1});

        assertThat(base)
                .isEqualTo(base)
                .isEqualTo(message(new byte[] {1}))
                .isNotEqualTo("not a message")
                .isNotEqualTo(new PluginMessage(
                        "x", CLUSTER, NODE, "q", new byte[] {1}, true, Map.of("h", 1), Map.of("p", "v"), 1))
                .isNotEqualTo(new PluginMessage(
                        "k", UUID.randomUUID(), NODE, "q", new byte[] {1}, true, Map.of("h", 1), Map.of("p", "v"), 1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        UUID.randomUUID(),
                        "q",
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k", CLUSTER, NODE, "other", new byte[] {1}, true, Map.of("h", 1), Map.of("p", "v"), 1))
                .isNotEqualTo(message(new byte[] {2}))
                .isNotEqualTo(new PluginMessage(
                        "k", CLUSTER, NODE, "q", new byte[] {1}, false, Map.of("h", 1), Map.of("p", "v"), 1))
                .isNotEqualTo(new PluginMessage(
                        "k", CLUSTER, NODE, "q", new byte[] {1}, true, Map.of("h", 2), Map.of("p", "v"), 1))
                .isNotEqualTo(new PluginMessage(
                        "k", CLUSTER, NODE, "q", new byte[] {1}, true, Map.of("h", 1), Map.of("p", "w"), 1))
                .isNotEqualTo(new PluginMessage(
                        "k", CLUSTER, NODE, "q", new byte[] {1}, true, Map.of("h", 1), Map.of("p", "v"), 2));
    }

    @Test
    void toStringReportsBodyLengthNeverContent() {
        assertThat(message("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString())
                .contains("body=6 bytes")
                .doesNotContain("secret");
        assertThat(message(null).toString()).contains("body=null");
    }
}
